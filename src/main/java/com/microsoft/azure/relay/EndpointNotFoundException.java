package com.microsoft.azure.relay;

//Represents an exception when the Relay HybridConnection/Endpoint should exist but was not present.
public class EndpointNotFoundException extends RelayException {

	private static final long serialVersionUID = 63770492444384139L;

	/**
	 * Creates a new instance of the EndpointNotFoundException class.
	 */
	public EndpointNotFoundException() {
		this.setTransient(false);
	}

	/**
	 * Creates a new instance of the EndpointNotFoundException class with a
	 * specified error message.
	 * 
	 * @param message The message that describes the error.
	 */
	public EndpointNotFoundException(String message) {
		super(message);
		this.setTransient(false);
	}

	/**
	 * Creates a new instance of the EndpointNotFoundException class with a
	 * specified error message and a reference to the inner exception that is the
	 * cause of this exception.
	 * 
	 * @param message        The message that describes the error.
	 * @param innerException The exception that is the cause of the current
	 *                       exception.
	 */
	public EndpointNotFoundException(String message, Exception innerException) {
		this(message, innerException, false);
	}

	/**
	 * Creates a new instance of the EndpointNotFoundException class with a
	 * specified error message and a reference to the inner exception that is the
	 * cause of this exception.
	 * 
	 * @param message        The message that describes the error.
	 * @param innerException The exception that is the cause of the current
	 *                       exception.
	 * @param isTransient    Whether this exception is transient and should be
	 *                       retried.
	 */
	protected EndpointNotFoundException(String message, Exception innerException, boolean isTransient) {
		super(message, innerException);
		this.setTransient(isTransient);
	}
}
