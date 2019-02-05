package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

class AsyncLock {
	private final Object thisLock = new Object();
	private final CompletableFuture<LockRelease> lockRelease;
	private InputQueue<Boolean> waiterQueue;
	private boolean locked;

	AsyncLock() {
		this.lockRelease = CompletableFuture.completedFuture(new LockRelease(this));
	}

	/**
	 * For Debug/Diagnostic purposes only.
	 * If you rely on this for anything real it may be out of date by the time you decide what to do.
	 */
	boolean isLocked() {
		synchronized (this.thisLock) {
			return this.locked;
		}
	}

	CompletableFuture<LockRelease> lockAsync() {
		return this.lockAsync(null);
	}

	CompletableFuture<LockRelease> lockAsync(Duration timeout) {
		synchronized (this.thisLock) {
			if (!this.locked) {
				// The lock is available;
				this.locked = true;
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
			synchronized (this.thisLock) {
				this.locked = true;
			}
			return this.lockRelease;
		});
	}

	private void release() {
		synchronized (this.thisLock) {
			if (this.locked) {
				this.locked = false;

				// If there's a waiter we signal them now
				if (this.waiterQueue != null && this.waiterQueue.getReadersQueueCount() > 0) {
					this.waiterQueue.enqueueAndDispatch(true, null, /* canDispatchOnThisThread */ false);
				}
			}
		}
	}

	final class LockRelease {
		private final AsyncLock asyncLock;

		private LockRelease(AsyncLock release) {
			this.asyncLock = release;
		}

		void release() {
			this.asyncLock.release();
		}
	}
}
