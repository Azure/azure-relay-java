package com.microsoft.azure.relay;

import java.net.URI;
import junit.framework.AssertionFailedError;

public class TestUtil {
	public static final String CONNECTION_STRING_ENV_VARIABLE_NAME = "RELAY_CONNECTION_STRING";
	public static final RelayConnectionStringBuilder CONNECTION_STRING_BUILDER = new RelayConnectionStringBuilder(System.getenv(CONNECTION_STRING_ENV_VARIABLE_NAME));
	public static final URI RELAY_NAMESPACE_URI = CONNECTION_STRING_BUILDER.getEndpoint();
	public static final String ENTITY_PATH = CONNECTION_STRING_BUILDER.getEntityPath();
	public static final String KEY_NAME = CONNECTION_STRING_BUILDER.getSharedAccessKeyName();
	public static final String KEY = CONNECTION_STRING_BUILDER.getSharedAccessKey();
		
	@SuppressWarnings("unchecked")
	public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
		try {
			executable.execute();
		}
		catch (Throwable actualException) {
			if (expectedType.isInstance(actualException)) {
				return (T) actualException;
			}
			else {
				String message = "Expected exception of type '" + expectedType + "' but found exception of type '" + actualException.getClass() + "' instead.";
				throw new AssertionFailedError(message);
			}
		}

		String message = "Expected exception of type '" + expectedType + "' to be thrown.";
		throw new AssertionFailedError(message);
	}
}
