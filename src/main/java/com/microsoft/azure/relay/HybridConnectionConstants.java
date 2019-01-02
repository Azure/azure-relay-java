package com.microsoft.azure.relay;

import java.time.Duration;

final class HybridConnectionConstants {
    protected static final String HYBRIDCONNECTION_REQUEST_URI = "/$hc";
    protected static final String SECURE_WEBSOCKET_SCHEME = "wss";
    protected static final int MAX_UNRECOGNIZED_JSON = 1024;

    // Names of query String options
    protected static final String QUERY_STRING_KEY_PREFIX = "sb-hc-";
    protected static final String ACTION = QUERY_STRING_KEY_PREFIX + "action"; // sb-hc-action
    protected static final String ID = QUERY_STRING_KEY_PREFIX + "id"; // sb-hc-id
    protected static final String STATUS_CODE = QUERY_STRING_KEY_PREFIX + "statusCode"; // sb-hc-statusCode
    protected static final String STATUS_DESCRIPTION = QUERY_STRING_KEY_PREFIX + "statusDescription"; // sb-hc-statusDescription
    protected static final String TOKEN = QUERY_STRING_KEY_PREFIX + "token"; // sb-hc-token
    protected static final String SAS_KEY_NAME = QUERY_STRING_KEY_PREFIX + "sas-key-name"; // sb-hc-sas-key-name
    protected static final String SAS_KEY = QUERY_STRING_KEY_PREFIX + "sas-key"; // sb-hc-sas-key

    protected static final Duration KEEP_ALIVE_INTERVAL = Duration.ofMinutes((long) 3.5);

    protected static class Actions
    {
        protected static final String LISTEN = "listen";
        protected static final String ACCEPT = "accept";
        protected static final String CONNECT = "connect";
        protected static final String REQUEST = "request";
    }

    protected static class Headers
    {
        protected static final String RELAY_USER_AGENT = "Relay-User-Agent";
        protected static final String SEC_WEBSOCKET_EXTENSIONS = "Sec-WebSocket-Extensions";
        protected static final String SEC_WEBSOCKET_KEY = "Sec-WebSocket-Key";
        protected static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
        protected static final String SEC_WEBSOCKET_VERSION = "Sec-WebSocket-Version";
    }
}
