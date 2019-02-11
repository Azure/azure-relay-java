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
		if (permits < 1) {
			throw new IllegalArgumentException("The size of semaphore cannot be less than 1");
		}
		this.limit = permits;
		synchronized (thisLock) {
			this.permits = permits;
		}

	}
	
	/**
	 * For Debug/Diagnostic purposes only.
	 * If you rely on this for anything real it may be out of date by the time you decide what to do.
	 */
	public int availablePermits() {
		synchronized (thisLock) {
			return this.permits;	
		}
	}

	public CompletableFuture<LockRelease> acquireAsync() {
		return this.acquireAsync(null);
	}
	
	public CompletableFuture<LockRelease> acquireAsync(Duration timeout) {
		return acquireAsync(1, timeout);
	}
	
	public CompletableFuture<LockRelease> acquireAsync(int permits) {
		return acquireAsync(permits, null);
	}
	
	public CompletableFuture<LockRelease> acquireAsync(int permits, Duration timeout) {
		CompletableFuture<?>[] releases;
		if (permits > limit) {
			return CompletableFutureUtil.fromException(
				new IllegalArgumentException("Cannot acquire more than its capacity."));
		}
		
		synchronized(thisLock) {
			int acquired = Math.min(this.permits, permits);
			
			this.permits -= acquired;
			if (acquired == permits) {
				return CompletableFuture.completedFuture(new LockRelease(this, permits));
			}
			
			// If we made it here the lock is not available yet
			if (this.waiterQueue == null) {
				this.waiterQueue = new InputQueue<Boolean>();
			}

			releases = new CompletableFuture<?>[permits];
			for (int i = 0; i < permits; i++) {
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
					this.permits -= permits;
				}
				return new LockRelease(this, permits);
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
		private final Object releaseLock = new Object();
		private final AsyncSemaphore asyncSem;
		int remaining;

		private LockRelease(AsyncSemaphore sem, int count) {
			this.asyncSem = sem;
			synchronized (releaseLock) {
				this.remaining = count;
			}
		}

		void release() {
			release(1);
		}
		
		void release(int count) {
			synchronized (releaseLock) {
				if (this.remaining < count) {
					throw new IllegalArgumentException("Cannot release more than owned.");
				}

				this.asyncSem.release(count);
				this.remaining -= count;
			}
		}
	}
}
