package com.microsoft.azure.relay;

class AsyncLock extends AsyncSemaphore {

	public AsyncLock() {
		super(0, 1);
	}
}
