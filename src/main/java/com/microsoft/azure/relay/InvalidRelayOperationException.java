package com.microsoft.azure.relay;

@SuppressWarnings("serial")
public class InvalidRelayOperationException extends RuntimeException {
	private boolean triansient;

	public boolean isTransient() {
		return this.triansient;
	}

	/**
	 * Creates a new instance of the InvalidOperationException class.
	 */
	public InvalidRelayOperationException() {
		this.triansient = false;
	}

	/**
	 * Creates a new instance of the InvalidOperationException class with a
	 * specified error message.
	 * 
	 * @param message The message that describes the error.
	 */
	public InvalidRelayOperationException(String message) {
		super(message);
		this.triansient = false;
	}

	/**
	 * Creates a new instance of the InvalidOperationException class with a
	 * specified error message and a reference to the inner exception that is the
	 * cause of this exception.
	 * 
	 * @param message        The message that describes the error.
	 * @param innerException The exception that is the cause of the current
	 *                       exception.
	 */
	public InvalidRelayOperationException(String message, Exception innerException) {
		this(message, innerException, false);
	}

	/**
	 * Creates a new instance of the InvalidOperationException class with a
	 * specified error message and a reference to the inner exception that is the
	 * cause of this exception.
	 * 
	 * @param message        The message that describes the error.
	 * @param innerException The exception that is the cause of the current
	 *                       exception.
	 * @param isTransient    Whether this exception is transient and should be
	 *                       retried.
	 */
	protected InvalidRelayOperationException(String message, Exception innerException, boolean isTransient) {
		super(message, innerException);
		this.triansient = isTransient;
	}
}
