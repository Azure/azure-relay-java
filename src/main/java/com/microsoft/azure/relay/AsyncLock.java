package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

class AsyncLock extends AsyncSemaphore {
	AsyncLock() {
		super(1);
	}

	boolean isLocked() {
		return this.getAvailableCount() == 0;
	}
}
