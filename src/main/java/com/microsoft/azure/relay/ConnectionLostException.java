package com.microsoft.azure.relay;

@SuppressWarnings("serial")
//An exception that occurs when a Listener gets disconnected from the Azure cloud service.
public class ConnectionLostException extends RelayException {

	/**
	 * Creates a new instance of the ConnectionLostException class.
	 */
	public ConnectionLostException() {
	}

	/**
	 * Creates a new instance of the ConnectionLostException class with a specified
	 * error message.
	 * 
	 * @param message The message that describes the error.
	 */
	public ConnectionLostException(String message) {
		super(message);
	}

	/**
	 * Creates a new instance of the ConnectionLostException class with a specified
	 * error message and a reference to the inner exception that is the cause of
	 * this exception.
	 * 
	 * @param message        The message that describes the error.
	 * @param innerException The exception that is the cause of the current
	 *                       exception.
	 */
	public ConnectionLostException(String message, Exception innerException) {
		super(message, innerException);
	}

}
