package com.microsoft.azure.relay;

class AsyncLock extends AsyncSemaphore {
	AsyncLock() {
		super(1);
	}

	boolean isLocked() {
		return this.availablePermits() == 0;
	}
}
