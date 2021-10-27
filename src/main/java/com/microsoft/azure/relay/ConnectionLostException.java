// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

//An exception that occurs when a Listener gets disconnected from the Azure cloud service.
public class ConnectionLostException extends RelayException {

	private static final long serialVersionUID = -5654006432009140373L;

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
