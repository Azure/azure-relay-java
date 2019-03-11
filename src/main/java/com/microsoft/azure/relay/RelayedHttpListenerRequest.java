package com.microsoft.azure.relay;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public final class RelayedHttpListenerRequest {
	private final String httpMethod;
	private final URI uri;
	private ByteArrayInputStream inputStream;
	private Map<String, String> headers;
	private boolean hasEntityBody;
	private InetSocketAddress remoteEndPoint;

	RelayedHttpListenerRequest(URI uri, String method, Map<String, String> requestHeaders) {
		this.httpMethod = method;
		this.uri = uri;
		this.inputStream = null;
		this.headers = new HashMap<String, String>();
		requestHeaders.forEach((k, v) -> this.headers.put(k, v));
	}
	
	boolean hasEntityBody() {
		return hasEntityBody;
	}

	void setHasEntityBody(boolean hasEntityBody) {
		this.hasEntityBody = hasEntityBody;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public ByteArrayInputStream getInputStream() {
		return inputStream;
	}

	void setInputStream(ByteArrayInputStream inputStream) {
		this.inputStream = inputStream;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public URI getUri() {
		return uri;
	}
	
	public InetSocketAddress getRemoteEndPoint() {
		return remoteEndPoint;
	}

	void setRemoteEndPoint(ListenerCommand.Endpoint remoteEndpoint) {
		
		if (remoteEndpoint != null) {
			InetSocketAddress inetAddress = new InetSocketAddress(remoteEndpoint.getAddress(), remoteEndpoint.getPort());
			this.remoteEndPoint = inetAddress;
		}
	}
}
