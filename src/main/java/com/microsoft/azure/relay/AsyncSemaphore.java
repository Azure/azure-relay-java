package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class AsyncSemaphore {
	private final Object thisLock = new Object();
	private final int limit;
	private InputQueue<Boolean> waiterQueue;
	private int permits;
	
	AsyncSemaphore(int permits) {
		this.limit = permits;
		synchronized(this.thisLock) {
			if (permits < 1) {
				throw new IllegalArgumentException("The size of semaphore cannot be less than 1");
			}
			this.permits = permits;
		}
	}
	
	/**
	 * For Debug/Diagnostic purposes only.
	 * If you rely on this for anything real it may be out of date by the time you decide what to do.
	 */
	int availablePermits() {
		synchronized(thisLock) {
			return this.permits;
		}
	}

	CompletableFuture<LockRelease> acquireAsync(AutoShutdownScheduledExecutor executor) {
		return this.lockAsync(1, null, executor);
	}
	
	CompletableFuture<LockRelease> acquireAsync(Duration timeout, AutoShutdownScheduledExecutor executor) {
		return lockAsync(1, timeout, executor);
	}
	
	CompletableFuture<LockRelease> acquireAsync(int count, AutoShutdownScheduledExecutor executor) {
		return lockAsync(count, null, executor);
	}
	
	CompletableFuture<LockRelease> lockAsync(int count, Duration timeout, AutoShutdownScheduledExecutor executor) {
		CompletableFuture<?>[] releases;
		if (count > limit) {
			return CompletableFutureUtil.fromException(
				new IllegalArgumentException("Cannot acquire more than its capacity."));
		}
		
		synchronized(thisLock) {
			int acquired = Math.min(this.availablePermits(), count);
			
			this.permits -= acquired;
			if (acquired == count) {
				return CompletableFuture.completedFuture(new LockRelease(count));
			}
			
			// If we made it here the lock is not available yet
			if (this.waiterQueue == null) {
				this.waiterQueue = new InputQueue<Boolean>(executor);
			}

			releases = new CompletableFuture<?>[count];
			for (int i = 0; i < count; i++) {
				releases[i] = (i < acquired) ? CompletableFuture.completedFuture(true) 
					: this.waiterQueue.dequeueAsync(timeout);
			}
		}
		
		return CompletableFuture.allOf(releases).handle((nullResult, ex) -> {
			// Could not complete successfully, give back the ones that we did get
			if (ex != null) {
				for (CompletableFuture<?> release : releases) {
					synchronized (thisLock) {
						if (!release.isCompletedExceptionally()) {
							this.waiterQueue.enqueueAndDispatch(true, null, false);
						}						
					}
				}
				throw new CompletionException(ex.getCause());
			} else {
				synchronized (this.thisLock) {
					this.permits -= count;
				}
				return new LockRelease(count);
			}
		});
	}
	
	/**
	  * Acquire the lock without blocking, execute the given asynchronous code, 
	  * and finally release the lock regardless if the given supplier threw during its execution
	  * @param timeout Maximum time to wait for the lock. Throws TimeoutException if the lock is not acquired after the timeout finishes
	  * @param executor The executor that executes the given supplier code
	  * @param supplier Code to be executed that returns a CompletionStage after obtaining the lock
	  * @return A CompletableFuture that completes when the lock is released after returning the desired CompletableFuture from the supplier
	  */
	public <T> CompletableFuture<T> lockThenCompose(Duration timeout, AutoShutdownScheduledExecutor executor, Supplier<? extends CompletionStage<T>> supplier) {
		AtomicReference<LockRelease> lockReleaseRef = new AtomicReference<LockRelease>();
		return acquireAsync(timeout, executor)
			.thenCompose((lockRelease) -> {
				lockReleaseRef.set(lockRelease);
				
				// Invoke the caller supplier function
				return supplier.get();
			})
			.whenComplete((result, ex) -> {
				LockRelease lockRelease = lockReleaseRef.get();
				if (lockRelease != null) {						
					lockRelease.release();
				}
			});
	}
	
	/**
	  * Acquire the lock without blocking, execute the given synchronous code, 
	  * and finally release the lock regardless if the given supplier threw during its execution
	  * @param timeout Maximum time to wait for the lock. Throws TimeoutException if the lock is not acquired after the timeout finishes
	  * @param executor The executor that executes the given supplier code
	  * @param supplier Code to be executed that provides a return value after obtaining the lock
	  * @return A CompletableFuture that completes when the lock is released after returning the desired return value from the supplier
	  */
	public <T> CompletableFuture<T> lockThenApply(Duration timeout, AutoShutdownScheduledExecutor executor, Supplier<T> supplier) {
		return acquireAsync(timeout, executor)
			.thenApply((lockRelease) -> {
				try {
					// Invoke the caller supplier function
					return supplier.get();
				} finally {
					lockRelease.release();
				}
			});
	}
	
	private void release(int count) {
		synchronized (this.thisLock) {
			this.permits += count;

			// If there's a waiter we signal them now
			if (this.waiterQueue != null) {
				for (int i = 0; i < count && this.waiterQueue.getReadersQueueCount() > 0; i++) {
					this.waiterQueue.enqueueAndDispatch(true, null, /* canDispatchOnThisThread */ false);
				}
			}
		}
	}

	public final class LockRelease {
		private final Object thisLock;
		private int remaining;

		private LockRelease(int count) {
			this.thisLock = new Object();
			this.remaining = count;
		}

		public void release() {
			release(1);
		}

		public void release(int count) {
			synchronized (this.thisLock) {
				if (this.remaining < count) {
					throw new IllegalArgumentException("Cannot release more than owned.");
				}

				AsyncSemaphore.this.release(count);
				this.remaining -= count;
			}
		}
	}
}
