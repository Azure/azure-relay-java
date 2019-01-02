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
    	
    	CompletableFuture<Boolean> wait = CompletableFuture.supplyAsync(() -> {
    		boolean acquired = false;
    		try {
    			if (duration != null) {
    				acquired = this.asyncSemaphore.tryAcquire(duration.toMillis(), TimeUnit.MILLISECONDS);
    			} else {
    				this.asyncSemaphore.acquire();
    				acquired = true;
    			}
			} catch (InterruptedException e) {
				acquired = false;
			}
    		return acquired;
    	});
    	
    	return wait.thenCompose((acquired) -> {
    		if (!acquired) {
    			throw new RuntimeException("Semaphore was not acquired");
    		}
    		return this.lockRelease;
    	});
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
