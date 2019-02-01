package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

class AsyncLock {
	private final Object thisLock = new Object();
	private final LockRelease lockRelease;
	private InputQueue<Boolean> waiterQueue;
	private boolean locked;

	AsyncLock() {
		this.lockRelease = new LockRelease(this);
		this.waiterQueue = new InputQueue<Boolean>();
		this.waiterQueue.enqueueAndDispatch(true);
	}

	boolean isLocked() {
		synchronized (this.thisLock) {
			return this.locked;
		}
	}
	
	CompletableFuture<LockRelease> lockAsync() {
		return this.lockAsync(null);
	}

	CompletableFuture<LockRelease> lockAsync(Duration duration) {
		synchronized (this.thisLock) {
			if (!this.locked) {
				this.locked = true;
				return this.waiterQueue.dequeueAsync(duration).thenApply(acquired -> this.lockRelease);
			}
		}
		
		AtomicReference<CompletableFuture<LockRelease>> waiter = new AtomicReference<CompletableFuture<LockRelease>>(null);
		Future<?> cancelTask = (duration != null) ? CompletableFutureUtil.executor.schedule(() -> {
			if (waiter.get() != null && !waiter.get().isDone()) {
				waiter.get().completeExceptionally(new TimeoutException("Semaphore was not acquired in time"));
			}
		}, duration.toMillis(), TimeUnit.MILLISECONDS) : null;

		// TODO: Add Duration/Cancel support to InputQueue<T>
		waiter.set(this.waiterQueue.dequeueAsync().handle((acquired, ex) -> {
			if (duration != null) {
				cancelTask.cancel(true);
			}
			if (acquired != true || ex != null) {
				if (ex != null) {
					throw new RuntimeException(ex);
				}
				throw new RuntimeException("AsyncLock was not acquired");
			}

			synchronized (this.thisLock) {
				this.locked = true;
				return this.lockRelease;	
			}
		}));
		return waiter.get();
	}

	private void release() {
		synchronized (this.thisLock) {
			this.waiterQueue.enqueueAndDispatch(true);
			this.locked = false;
		}
	}

	final class LockRelease {
		private final AsyncLock asyncLock;

		private LockRelease(AsyncLock lock) {
			this.asyncLock = lock;
		}

		void release() {
			this.asyncLock.release();
		}
	}
}
