package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncSemaphore {
	private final Object thisLock = new Object();
	private InputQueue<Boolean> waiterQueue;
	private AtomicInteger available = new AtomicInteger();
	private int limit;
	
	AsyncSemaphore(int semaphoreSize) {		
		if (semaphoreSize < 1) {
			throw new IllegalArgumentException("The size of semaphore cannot be less than 1");
		}
		this.available.set(semaphoreSize);
		this.limit = semaphoreSize;
	}
	
	/**
	 * For Debug/Diagnostic purposes only.
	 * If you rely on this for anything real it may be out of date by the time you decide what to do.
	 */
	int getAvailableCount() {
		return this.available.get();
	}

	CompletableFuture<LockRelease> lockAsync(AutoShutdownScheduledExecutor executor) {
		return this.lockAsync(1, null, executor);
	}
	
	CompletableFuture<LockRelease> lockAsync(Duration timeout, AutoShutdownScheduledExecutor executor) {
		return lockAsync(1, timeout, executor);
	}
	
	CompletableFuture<LockRelease> lockAsync(int count, AutoShutdownScheduledExecutor executor) {
		return lockAsync(count, null, executor);
	}
	
	CompletableFuture<LockRelease> lockAsync(int count, Duration timeout, AutoShutdownScheduledExecutor executor) {
		CompletableFuture<?>[] releases;
		if (count > limit) {
			return CompletableFutureUtil.fromException(
				new IllegalArgumentException("Cannot acquire more than its capacity."));
		}
		
		synchronized(thisLock) {
			int acquired = Math.min(this.getAvailableCount(), count);
			
			subtractCount(acquired);
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
					subtractCount(count);
				}
				return new LockRelease(this, count);
			}
		});
	}
	
	private void subtractCount(int count) {
		int current = this.getAvailableCount();
		this.available.set(current - count);
	}
	
	private void release(int count) {
		synchronized (this.thisLock) {
			this.available.addAndGet(count);

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
		AtomicInteger remaining = new AtomicInteger();

		private LockRelease(AsyncSemaphore sem, int count) {
			this.asyncSem = sem;
			this.remaining.set(count);
		}

		void release() {
			release(1);
		}
		
		void release(int count) {
			if (this.remaining.get() < count) {
				throw new IllegalArgumentException("Cannot release more than owned.");
			}
			this.asyncSem.release(count);
			
			int current = this.remaining.get();
			this.remaining.set(current - count);
		}
	}
}
