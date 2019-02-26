package com.microsoft.azure.relay;

import java.util.concurrent.ScheduledExecutorService;

class AsyncLock extends AsyncSemaphore {
	AsyncLock(ScheduledExecutorService executor) {
		super(1, executor);
	}

	/**
	 * For Debug/Diagnostic purposes only.
	 * If you rely on this for anything real it may be out of date by the time you decide what to do.
	 */
	boolean isLocked() {
		return this.availablePermits() == 0;
	}
}
