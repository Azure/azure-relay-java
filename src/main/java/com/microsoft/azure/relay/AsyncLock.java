package com.microsoft.azure.relay;

class AsyncLock extends AsyncSemaphore {
	AsyncLock(AutoShutdownScheduledExecutor executor) {
		super(1);
	}

	boolean isLocked() {
		return this.getAvailableCount() == 0;
	}
}
