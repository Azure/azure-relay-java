package com.microsoft.azure.relay;

import java.util.List;
import java.util.Map;
import javax.websocket.ClientEndpointConfig;

public class HybridConnectionEndpointConfigurator extends ClientEndpointConfig.Configurator {
	private static Map<String, List<String>> currentHeaders;
	
	protected static Map<String, List<String>> getHeaders() {
		return currentHeaders;
	}

	protected static void setHeaders(Map<String, List<String>> headers) {
		currentHeaders = headers;
	}
	
	@Override
	public void beforeRequest(Map<String, List<String>> requestHeaders) {
		requestHeaders.putAll(currentHeaders);
	}
}
