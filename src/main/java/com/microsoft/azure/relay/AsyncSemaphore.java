// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class AsyncSemaphore {
	private final Object thisLock = new Object();
	private final ScheduledExecutorService executor;
	private final int limit;
	private InputQueue<Boolean> waiterQueue;
	private int permits;
	
	public AsyncSemaphore(int permits, ScheduledExecutorService executor) {
		this.limit = permits;
		this.executor = executor;
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
	public int availablePermits() {
		synchronized(thisLock) {
			return this.permits;
		}
	}

	public CompletableFuture<LockRelease> acquireAsync() {
		return acquireAsync(1, null);
	}
	
	public CompletableFuture<LockRelease> acquireAsync(Duration timeout) {
		return acquireAsync(1, timeout);
	}
	
	public CompletableFuture<LockRelease> acquireAsync(int count) {
		return acquireAsync(count, null);
	}
	
	public CompletableFuture<LockRelease> acquireAsync(int count, Duration timeout) {
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
	  * @param <T> The return type of the CompletableFuture
	  * @param timeout Maximum time to wait for the lock. Throws TimeoutException if the lock is not acquired after the timeout finishes
	  * @param supplier Code to be executed that returns a CompletionStage after obtaining the lock
	  * @return A CompletableFuture that completes when the lock is released after returning the desired CompletableFuture from the supplier
	  */
	public <T> CompletableFuture<T> acquireThenCompose(Duration timeout, Supplier<? extends CompletionStage<T>> supplier) {
		AtomicReference<LockRelease> lockReleaseRef = new AtomicReference<LockRelease>();
		return acquireAsync(timeout)
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
	  * @param <T> The return type of the CompletableFuture
	  * @param timeout Maximum time to wait for the lock. Throws TimeoutException if the lock is not acquired after the timeout finishes
	  * @param supplier Code to be executed that provides a return value after obtaining the lock
	  * @return A CompletableFuture that completes when the lock is released after returning the desired return value from the supplier
	  */
	public <T> CompletableFuture<T> acquireThenApply(Duration timeout, Supplier<T> supplier) {
		return acquireAsync(timeout)
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
			this.release(1);
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
