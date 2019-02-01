package com.microsoft.azure.relay;

import java.time.Duration;

final class RelayConstants {
	
	private RelayConstants() { }
	
	protected static final String MANAGEMENT_API_VERSION = "2016-07";
	protected static final String MANAGEMENT_NAMESPACE = "http://schemas.microsoft.com/netservices/2010/10/servicebus/connect";
	protected static final String HYBRID_CONNECTION_SCHEME = "sb";
	protected static final String SERVICEBUS_AUTHORIZATION_HEADER_NAME = "ServiceBusAuthorization";
	protected static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofMinutes(1);
	protected static final Duration CLIENT_MINIMUM_TOKEN_REFRESH_INTERVAL = Duration.ofMinutes(4);
	protected static final Duration MAX_DURATION = Duration.ofMillis(Integer.MAX_VALUE);
	protected static final Duration MIN_DURATION = Duration.ofMillis(Integer.MIN_VALUE);
	protected static final int DEFAULT_CONNECTION_BUFFER_SIZE = 64 * 1024;
}
