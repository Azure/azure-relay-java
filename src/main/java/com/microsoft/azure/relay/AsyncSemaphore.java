package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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
				return CompletableFuture.completedFuture(new LockRelease(this, count));
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
				return new LockRelease(this, count);
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

	final class LockRelease {
		private final AsyncSemaphore asyncSem;
		private int remaining;

		private LockRelease(AsyncSemaphore sem, int count) {
			this.asyncSem = sem;
			synchronized(this.asyncSem) {
				this.remaining = count;
			}
		}

		void release() {
			release(1);
		}
		
		void release(int count) {
			synchronized(this.asyncSem) {
				if (this.remaining < count) {
					throw new IllegalArgumentException("Cannot release more than owned.");
				}
				
				this.asyncSem.release(count);
				this.remaining -= count;			
			}

		}
	}
}
