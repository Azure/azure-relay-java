package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

class AsyncSemaphore {
	private final Object thisLock = new Object();
	private final CompletableFuture<LockRelease> lockRelease;
	private InputQueue<Boolean> waiterQueue;
	private final int limit;
	private int count;

	AsyncSemaphore(int limit) {
		this(limit, limit);
	}

	AsyncSemaphore(int count, int limit) {
		this.count = count;
		this.limit = limit;
		this.lockRelease = CompletableFuture.completedFuture(new LockRelease(this));
	}
	
	/**
	 * For Debug/Diagnostic purposes only.
	 * If you rely on this for anything real it may be out of date by the time you decide what to do.
	 */
	boolean isLocked() {
		synchronized (this.thisLock) {
			return this.count == 0;
		}
	}

	CompletableFuture<LockRelease> lockAsync() {
		return this.lockAsync(null);
	}

	CompletableFuture<LockRelease> lockAsync(Duration timeout) {
		synchronized (this.thisLock) {
			if (this.count > 0) {
				// The lock is available;
				this.count--;
				return this.lockRelease;
			}
		}
		
		// If we made it here the lock is not available yet
		if (this.waiterQueue == null) {
			this.waiterQueue = new InputQueue<Boolean>();
		}

		return this.waiterQueue.dequeueAsync(timeout).thenCompose((acquired) -> {
			if (!acquired) {
				throw new RuntimeException("Semaphore was not acquired");
			}

			return this.lockRelease;
		});
	}

	private void release() {
		synchronized (this.thisLock) {
			// If there's a waiter we signal them now
			if (this.waiterQueue != null && this.waiterQueue.getReadersQueueCount() > 0) {
				this.waiterQueue.enqueueAndDispatch(true, null, /* canDispatchOnThisThread */ false);
			} else {
				if (this.count == this.limit) {
					throw new RuntimeException("Adding the specified count to the semaphore would cause it to exceed its maximum count.");
				}
				
				this.count++;
			}
		}
	}

	final class LockRelease {
		private final AsyncSemaphore asyncLock;

		private LockRelease(AsyncSemaphore release) {
			this.asyncLock = release;
		}

		void release() {
			this.asyncLock.release();
		}
	}
}
