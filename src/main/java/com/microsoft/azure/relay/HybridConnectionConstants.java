package com.microsoft.azure.relay;

import java.time.Duration;

public final class HybridConnectionConstants {
    public static final String HYBRIDCONNECTION_REQUEST_URI = "/$hc";
    public static final String SECURE_WEBSOCKET_SCHEME = "wss";
    public static final int MAX_UNRECOGNIZED_JSON = 1024;
    public static final boolean DEFAULT_USE_BUILTIN_CLIENT_WEBSOCKET = false;

    // Names of query String options
    public static final String QUERY_STRING_KEY_PREFIX = "sb-hc-";
    public static final String ACTION = QUERY_STRING_KEY_PREFIX + "action"; // sb-hc-action
    public static final String ID = QUERY_STRING_KEY_PREFIX + "id"; // sb-hc-id
    public static final String STATUS_CODE = QUERY_STRING_KEY_PREFIX + "statusCode"; // sb-hc-statusCode
    public static final String STATUS_DESCRIPTION = QUERY_STRING_KEY_PREFIX + "statusDescription"; // sb-hc-statusDescription
    public static final String TOKEN = QUERY_STRING_KEY_PREFIX + "token"; // sb-hc-token
    public static final String SAS_KEY_NAME = QUERY_STRING_KEY_PREFIX + "sas-key-name"; // sb-hc-sas-key-name
    public static final String SAS_KEY = QUERY_STRING_KEY_PREFIX + "sas-key"; // sb-hc-sas-key

    public static final Duration KEEP_ALIVE_INTERVAL = Duration.ofMinutes((long) 3.5);

    public static class Actions
    {
        public static final String LISTEN = "listen";
        public static final String ACCEPT = "accept";
        public static final String CONNECT = "connect";
        public static final String REQUEST = "request";
    }

    public static class Headers
    {
        public static final String RELAY_USER_AGENT = "Relay-User-Agent";
        public static final String SEC_WEBSOCKET_EXTENSIONS = "Sec-WebSocket-Extensions";
        public static final String SEC_WEBSOCKET_KEY = "Sec-WebSocket-Key";
        public static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
        public static final String SEC_WEBSOCKET_VERSION = "Sec-WebSocket-Version";
    }
}
