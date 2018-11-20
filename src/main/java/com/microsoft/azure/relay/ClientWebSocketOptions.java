package com.microsoft.azure.relay;

import java.net.Proxy;
import java.time.Duration;

public abstract class ClientWebSocketOptions {
    Proxy proxy;
    Duration keepAliveInterval;

    public Proxy getProxy() {
		return proxy;
	}

	public void setProxy(Proxy proxy) {
		this.proxy = proxy;
	}

	public Duration getKeepAliveInterval() {
		return keepAliveInterval;
	}

	public void setKeepAliveInterval(Duration keepAliveInterval) {
		this.keepAliveInterval = keepAliveInterval;
	}

	abstract void addSubProtocol(String subProtocol);

    abstract void setBuffer(int receiveBufferSize, int sendBufferSize);

    abstract void setRequestHeader(String name, String value);
}