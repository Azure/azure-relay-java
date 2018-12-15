package com.microsoft.azure.relay;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public final class RelayedHttpListenerRequest {
	private String httpMethod;
	private URI url;
	private ByteBuffer inputStream;
	private Map<String, String> headers;
	private boolean hasEntityBody;
	private InetSocketAddress remoteEndPoint;
	
	
    public boolean hasEntityBody() {
		return hasEntityBody;
	}

	public void setHasEntityBody(boolean hasEntityBody) {
		this.hasEntityBody = hasEntityBody;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public ByteBuffer getInputStream() {
		return inputStream;
	}
	
	public void setInputStream(ByteBuffer inputStream) {
		this.inputStream = inputStream;
	}
	
	public Map<String, String> getHeaders() {
		return headers;
	}

	public InetSocketAddress getRemoteEndPoint() {
		return remoteEndPoint;
	}
	
	public URI getUrl() {
		return url;
	}

	public RelayedHttpListenerRequest(URI uri, String method, Map<String, String> requestHeaders) {
        this.httpMethod = method;
        this.url = uri;
        this.inputStream = null;
        this.headers = new HashMap<String, String>();
        requestHeaders.forEach((k, v) -> this.headers.put(k, v));
    }

    protected void setRemoteAddress(ListenerCommand.Endpoint remoteEndpoint) {
        String remoteAddress = (remoteEndpoint == null) ? null : remoteEndpoint.getAddress();
        
        if (!StringUtil.isNullOrEmpty(remoteAddress)) {
        	InetSocketAddress inetAddress = new InetSocketAddress(remoteEndpoint.getAddress(), remoteEndpoint.getPort());
        	
            if (inetAddress != null) {
                this.remoteEndPoint = inetAddress;
            }
            // TODO: trace
//            else {
//                RelayEventSource.Log.Warning(this, "Unable to parse 'remoteEndpoint.address'.");
//            }
        }
    }
}
