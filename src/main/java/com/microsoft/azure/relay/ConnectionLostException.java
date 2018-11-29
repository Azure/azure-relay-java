package com.microsoft.azure.relay;

public class ConnectionLostException extends RelayException {

	public ConnectionLostException() {
	}

	public ConnectionLostException(String message) {
		super(message);
	}

	public ConnectionLostException(String message, Exception innerException) {
		super(message, innerException);
	}

}
