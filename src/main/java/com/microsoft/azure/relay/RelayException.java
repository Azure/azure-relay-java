package com.microsoft.azure.relay;

@SuppressWarnings("serial")
public class RelayException extends Exception {
	private final static String DEFAULT_ERROR_MESSAGE = "Azure Relay has run into an invalid state.";
	private boolean isTransient;

	/**
	 * Creates a new instance of the RelayException class.
	 */
	RelayException() {
		this(DEFAULT_ERROR_MESSAGE);
	}

	/**
	 * Creates a new instance of the RelayException class with a specified error
	 * message.
	 * 
	 * @param message The message that describes the error.
	 */
	RelayException(String message) {
		super(message);
		this.isTransient = true;
	}

	/**
	 * Creates a new instance of the RelayException class with a specified error
	 * message and a reference to the inner exception that is the cause of this
	 * exception.
	 * 
	 * @param message        The message that describes the error.
	 * @param innerException The exception that is the cause of the current
	 *                       exception.
	 */
	public RelayException(String message, Exception innerException) {
		super(message, innerException);
		this.isTransient = true;
	}
	
	/**
	 * A value indicating whether the exception is transient. Check this property to
	 * determine if the operation should be retried.
	 * 
	 * @return True if the exception is transient; otherwise, false.
	 */
	public boolean isTransient() {
		return isTransient;
	}

	protected void setTransient(boolean isTransient) {
		this.isTransient = isTransient;
	}
}
