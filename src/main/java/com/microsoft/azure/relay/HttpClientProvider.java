package com.microsoft.azure.relay;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HttpClientProvider {
    public HttpClient getHttpClient() {
        return new HttpClient(new SslContextFactory.Client());
    }
}
