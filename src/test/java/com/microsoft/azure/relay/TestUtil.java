package com.microsoft.azure.relay;

import java.net.URI;

public class TestUtil {
	public static final String CONNECTION_STRING_ENV_VARIABLE_NAME = "RELAY_CONNECTION_STRING";
	public static final RelayConnectionStringBuilder CONNECTION_STRING_BUILDER = new RelayConnectionStringBuilder(System.getenv(CONNECTION_STRING_ENV_VARIABLE_NAME));
	public static final URI RELAY_NAMESPACE_URI = CONNECTION_STRING_BUILDER.getEndpoint();
	public static final String ENTITY_PATH = CONNECTION_STRING_BUILDER.getEntityPath();
	public static final String KEY_NAME = CONNECTION_STRING_BUILDER.getSharedAccessKeyName();
	public static final String KEY = CONNECTION_STRING_BUILDER.getSharedAccessKey();
}
