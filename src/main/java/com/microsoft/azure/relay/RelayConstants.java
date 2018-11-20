package com.microsoft.azure.relay;

import java.time.Duration;

public final class RelayConstants {
    public static final String MANAGEMENT_API_VERSION = "2016-07";
    public static final String MANAGEMENT_NAMESPACE = "http://schemas.microsoft.com/netservices/2010/10/servicebus/connect";
    public static final String HYBRID_CONNECTION_SCHEME = "sb";
    public static final String SERVICEBUS_AUTHORIZATION_HEADER_NAME = "ServiceBusAuthorization";
    public static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofMinutes(1);
    public static final Duration CLIENT_MINIMUM_TOKEN_REFRESH_INTERVAL = Duration.ofMinutes(4);
    public static final Duration MAX_DURATION = Duration.ofMillis(Integer.MAX_VALUE);
    public static final Duration MIN_DURATION = Duration.ofMillis(Integer.MIN_VALUE);
    public static final int DEFAULT_CONNECTION_BUFFER_SIZE = 64 * 1024;

    static class Claims
    {
        public static final String LISTEN = "Listen";
        public static final String SEND = "Send";
    }

    static class WebSocketHeaders
    {
        public static final String SEC_WEB_SOCKET_ACCEPT = "Sec-WebSocket-Accept";
        public static final String SEC_WEB_SOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
        public static final String SEC_WEB_SOCKET_KEY = "Sec-WebSocket-Key";
        public static final String SEC_WEB_SOCKET_VERSION = "Sec-WebSocket-Version";
    }
}
