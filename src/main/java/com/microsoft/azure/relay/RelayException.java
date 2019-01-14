package com.microsoft.azure.relay;

@SuppressWarnings("serial")
class RelayException extends Exception {

	private boolean isTransient;

	/**
	 * A value indicating whether the exception is transient. Check this property to
	 * determine if the operation should be retried.
	 * 
	 * @return True if the exception is transient; otherwise, false.
	 */
	public boolean isTransient() {
		return isTransient;
	}

	public void setTransient(boolean isTransient) {
		this.isTransient = isTransient;
	}

	/**
	 * Creates a new instance of the RelayException class.
	 */
	public RelayException() {
		this.isTransient = true;
	}

	/**
	 * Creates a new instance of the RelayException class with a specified error
	 * message.
	 * 
	 * @param message The message that describes the error.
	 */
	public RelayException(String message) {
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
}
