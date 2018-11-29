package com.microsoft.azure.relay;

public class RelayException extends Exception {
	
    /// <summary>A value indicating whether the exception is transient. Check this property
    /// to determine if the operation should be retried.</summary> 
    /// <value>true if the exception is transient; otherwise, false.</value>
	private boolean isTransient;
	
    public boolean isTransient() {
		return isTransient;
	}

	protected void setTransient(boolean isTransient) {
		this.isTransient = isTransient;
	}

	/// <summary>
    /// Creates a new instance of the <see cref="RelayException"/> class.
    /// </summary>
    public RelayException() {
        this.isTransient = true;
    }

    /// <summary>
    /// Creates a new instance of the <see cref="RelayException"/> class with a specified error message.
    /// </summary>
    /// <param name="message">The message that describes the error.</param>
    public RelayException(String message) {
    	super(message);
        this.isTransient = true;
    }

    /// <summary>
    /// Creates a new instance of the <see cref="RelayException"/> class with a specified error message and a reference to the inner exception that is the cause of this exception.
    /// </summary>
    /// <param name="message">The message that describes the error.</param>
    /// <param name="innerException">The exception that is the cause of the current exception.</param>
    public RelayException(String message, Exception innerException) {
    	super(message, innerException);
        this.isTransient = true;
    }

    /// <summary>
    /// Creates a new instance of the <see cref="RelayException"/> class with serialized data.
    /// </summary>
    /// <param name="info">The <see cref="SerializationInfo" /> that holds the serialized object data about the exception being thrown. </param>
    /// <param name="context">The <see cref="StreamingContext" /> that contains contextual information about the source or destination. </param>
//    protected RelayException(SerializationInfo info, StreamingContext context) : base(info, context) {
//        this.IsTransient = true;
//    }

}
