package com.microsoft.azure.relay;

import java.util.Map;

public class TestUtil {
	public static final String CONNECTION_STRING_ENV_VARIABLE_NAME = "RELAY_CONNECTION_STRING";
	static Map<String, String> connectionParams = StringUtil.parseConnectionString(System.getenv(CONNECTION_STRING_ENV_VARIABLE_NAME));
	public static final String RELAY_NAME_SPACE = connectionParams.get("Endpoint");
	public static final String CONNECTION_STRING = connectionParams.get("EntityPath");
	public static final String KEY_NAME = connectionParams.get("SharedAccessKeyName");
	public static final String KEY = connectionParams.get("SharedAccessKey");
}
