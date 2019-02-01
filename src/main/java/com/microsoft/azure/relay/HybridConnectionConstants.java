package com.microsoft.azure.relay;

import java.time.Duration;

final class HybridConnectionConstants {
	static final String HYBRIDCONNECTION_REQUEST_URI = "/$hc";
	static final String SECURE_WEBSOCKET_SCHEME = "wss";
	static final int MAX_UNRECOGNIZED_JSON = 1024;

	// Names of query String options
	static final String QUERY_STRING_KEY_PREFIX = "sb-hc-";
	static final String ACTION = QUERY_STRING_KEY_PREFIX + "action"; // sb-hc-action
	static final String ID = QUERY_STRING_KEY_PREFIX + "id"; // sb-hc-id
	static final String STATUS_CODE = QUERY_STRING_KEY_PREFIX + "statusCode"; // sb-hc-statusCode
	static final String STATUS_DESCRIPTION = QUERY_STRING_KEY_PREFIX + "statusDescription"; // sb-hc-statusDescription
	static final String TOKEN = QUERY_STRING_KEY_PREFIX + "token"; // sb-hc-token
	static final String SAS_KEY_NAME = QUERY_STRING_KEY_PREFIX + "sas-key-name"; // sb-hc-sas-key-name
	static final String SAS_KEY = QUERY_STRING_KEY_PREFIX + "sas-key"; // sb-hc-sas-key

	static final Duration KEEP_ALIVE_INTERVAL = Duration.ofMinutes((long) 3.5);

	static class Actions {
		static final String LISTEN = "listen";
		static final String ACCEPT = "accept";
		static final String CONNECT = "connect";
		static final String REQUEST = "request";
		static final String RESPONSE = "response";
	}

	static class Headers {
		static final String RELAY_USER_AGENT = "Relay-User-Agent";
		static final String SEC_WEBSOCKET_EXTENSIONS = "Sec-WebSocket-Extensions";
		static final String SEC_WEBSOCKET_KEY = "Sec-WebSocket-Key";
		static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
		static final String SEC_WEBSOCKET_VERSION = "Sec-WebSocket-Version";
	}
	
	private HybridConnectionConstants() {}
}
