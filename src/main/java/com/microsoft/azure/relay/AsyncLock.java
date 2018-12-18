package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

class AsyncLock {
    final Semaphore asyncSemaphore;
    final CompletableFuture<LockRelease> lockRelease;
    boolean disposed;

    protected AsyncLock() {
        this.asyncSemaphore = new Semaphore(1);
        this.lockRelease = CompletableFuture.completedFuture(new LockRelease(this));
    }

    protected CompletableFuture<LockRelease> lockAsync() {
    	return this.lockAsync(null);
    }

    protected CompletableFuture<LockRelease> lockAsync(Duration duration) {
    	
    	CompletableFuture<AsyncLock> wait = CompletableFuture.supplyAsync(() -> {
    		try {
    			if (duration != null) {
    				this.asyncSemaphore.tryAcquire(duration.toMillis(), TimeUnit.MILLISECONDS);
    			} else {
    				this.asyncSemaphore.acquire();
    			}
			} catch (InterruptedException e) {
				// TODO: exception
                // AggregateException.GetBaseException gets the first AggregateException with more than one inner exception
                // OR the first exception that's not an AggregateException.
//                throw t.Exception.GetBaseException().Rethrow();
				throw new RuntimeException("interrupted");
			}
    		return this;
    	});
    	
    	if (wait.isDone()) {
    		return this.lockRelease;
    	}
    	
    	return wait.thenApply((state) -> new LockRelease((AsyncLock) state));
    }

    protected final class LockRelease {
        final AsyncLock asyncLockRelease;

        protected LockRelease(AsyncLock release) {
            this.asyncLockRelease = release;
        }

        public void release() {
        	this.asyncLockRelease.asyncSemaphore.release();
        }
    }
}
