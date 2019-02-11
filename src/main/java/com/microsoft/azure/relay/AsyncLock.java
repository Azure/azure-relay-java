package com.microsoft.azure.relay;

class AsyncLock extends AsyncSemaphore {
	
	/**
	 * For Debug/Diagnostic purposes only.
	 * If you rely on this for anything real it may be out of date by the time you decide what to do.
	 */
	AsyncLock() {
		super(1);
	}

	boolean isLocked() {
		return this.availablePermits() == 0;
	}
}
