package com.microsoft.azure.relay;

public class EndpointNotFoundException extends RelayException {

    /// <summary>
    /// Creates a new instance of the <see cref="EndpointNotFoundException"/> class.
    /// </summary>
    public EndpointNotFoundException() {
        this.setTransient(false);
    }

    /// <summary>
    /// Creates a new instance of the <see cref="EndpointNotFoundException"/> class with a specified error message.
    /// </summary>
    /// <param name="message">The message that describes the error.</param>
    public EndpointNotFoundException(String message) {
    	super(message);
        this.setTransient(false);
    }

    /// <summary>
    /// Creates a new instance of the <see cref="EndpointNotFoundException"/> class with a specified error message and a reference to the inner exception that is the cause of this exception.
    /// </summary>
    /// <param name="message">The message that describes the error.</param>
    /// <param name="innerException">The exception that is the cause of the current exception.</param>
    public EndpointNotFoundException(String message, Exception innerException) {
    	this(message, innerException, false);
    }

    /// <summary>
    /// Creates a new instance of the <see cref="EndpointNotFoundException"/> class with a specified error message and a reference to the inner exception that is the cause of this exception.
    /// </summary>
    /// <param name="message">The message that describes the error.</param>
    /// <param name="innerException">The exception that is the cause of the current exception.</param>
    /// <param name="isTransient">Whether this exception is transient and should be retried.</param>
    protected EndpointNotFoundException(String message, Exception innerException, boolean isTransient) {
    	super(message, innerException);
        this.setTransient(isTransient);
    }

    /// <summary>
    /// Creates a new instance of the <see cref="EndpointNotFoundException"/> class with serialized data.
    /// </summary>
    /// <param name="info">The <see cref="SerializationInfo" /> that holds the serialized object data about the exception being thrown. </param>
    /// <param name="context">The <see cref="StreamingContext" /> that contains contextual information about the source or destination. </param>
//    protected EndpointNotFoundException(SerializationInfo info, StreamingContext context) : base(info, context) {
//        this.setTransient(false);
//    }
}
