package com.microsoft.azure.relay;

public class TestUtil {
	public static final String CONNECTION_STRING_ENV_VARIABLE_NAME = "RELAY_CONNECTION_STRING";
	static RelayConnectionStringBuilder connectionParams = new RelayConnectionStringBuilder(System.getenv(CONNECTION_STRING_ENV_VARIABLE_NAME));
	public static final String RELAY_NAME_SPACE = connectionParams.getEndpoint().toString();
	public static final String CONNECTION_STRING = connectionParams.getEntityPath();
	public static final String KEY_NAME = connectionParams.getSharedAccessKeyName();
	public static final String KEY = connectionParams.getSharedAccessKey();
}
