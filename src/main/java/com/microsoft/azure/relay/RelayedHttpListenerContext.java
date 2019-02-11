package com.microsoft.azure.relay;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.eclipse.jetty.http.HttpStatus;

public class RelayedHttpListenerContext {
	private static final Duration ACCEPT_TIMEOUT = Duration.ofSeconds(20);
	private String cachedToString;
	private final RelayedHttpListenerRequest request;
	private final RelayedHttpListenerResponse response;
	private final TrackingContext trackingContext;
	private final HybridConnectionListener listener;

	/**
	 * @return Returns the request object that resembles the http request object in
	 *         Hybridconnection
	 */
	public RelayedHttpListenerRequest getRequest() {
		return request;
	}

	/**
	 * @return Returns the response object that resembles the http request object in
	 *         Hybridconnection
	 */
	public RelayedHttpListenerResponse getResponse() {
		return response;
	}

	/**
	 * @return Returns the TrackingContext associated with this instance
	 */
	public TrackingContext getTrackingContext() {
		return trackingContext;
	}

	protected HybridConnectionListener getListener() {
		return listener;
	}

	protected RelayedHttpListenerContext(HybridConnectionListener listener, URI requestUri, String trackingId,
			String method, Map<String, String> requestHeaders) {
		this.listener = listener;
		this.trackingContext = TrackingContext.create(trackingId, requestUri);
		this.request = new RelayedHttpListenerRequest(requestUri, method, requestHeaders);
		this.response = new RelayedHttpListenerResponse(this);

		this.flowSubProtocol();
	}

	/**
	 * @return Returns a String that represents the current object. Includes a
	 *         TrackingId for end to end correlation.
	 */
	@Override
	public String toString() {
		if (this.cachedToString != null) {
			return this.cachedToString;
		} else {
			return this.cachedToString = "RelayedHttpListenerContext" + "(" + this.trackingContext + ")";
		}
	}

	protected CompletableFuture<ClientWebSocket> acceptAsync(URI rendezvousUri) {
		// TODO: subprotocol
		// If we are accepting a sub-protocol handle that here
//        String subProtocol = this.response.getHeaders().get(HybridConnectionConstants.Headers.SEC_WEBSOCKET_PROTOCOL);
//        if (!StringUtil.isNullOrEmpty(subProtocol)) {
//            clientWebSocket.Options.AddSubProtocol(subProtocol);
//        }

		ClientWebSocket webSocket = new ClientWebSocket(this.trackingContext, HybridConnectionListener.EXECUTOR);
		return webSocket.connectAsync(rendezvousUri, ACCEPT_TIMEOUT).thenApply(result -> webSocket);
	}

	protected CompletableFuture<Void> rejectAsync(URI rendezvousUri) {
		
		if (this.response.getStatusCode() == HttpStatus.CONTINUE_100) {
			this.response.setStatusCode(HttpStatus.BAD_REQUEST_400);
			this.response.setStatusDescription("Rejected by user code");
		}
		
		try {
			StringBuilder builder = new StringBuilder(rendezvousUri.toString());
			builder.append("&").append(HybridConnectionConstants.STATUS_CODE).append("=")
					.append(this.response.getStatusCode());
			builder.append("&").append(HybridConnectionConstants.STATUS_DESCRIPTION).append("=")
					.append(URLEncoder.encode(this.response.getStatusDescription(), StringUtil.UTF8.name()));
			URI rejectURI = new URI(builder.toString());
			
			ClientWebSocket webSocket = new ClientWebSocket(this.trackingContext, HybridConnectionListener.EXECUTOR);
			return webSocket.connectAsync(rejectURI, ACCEPT_TIMEOUT).thenCompose((result) -> webSocket.closeAsync());
		} catch (IOException | URISyntaxException e) {
			return CompletableFutureUtil.fromException(e);
		}
	}

	private void flowSubProtocol() {
		// By default use the first sub-protocol (if present)
		String subProtocol = this.request.getHeaders().get(HybridConnectionConstants.Headers.SEC_WEBSOCKET_PROTOCOL);

		if (!StringUtil.isNullOrEmpty(subProtocol)) {

			int separatorIndex = subProtocol.indexOf(',');
			if (separatorIndex >= 0) {
				// more than one sub-protocol in headers, only use the first.
				subProtocol = subProtocol.substring(0, separatorIndex);
			}
			this.response.getHeaders().put(HybridConnectionConstants.Headers.SEC_WEBSOCKET_PROTOCOL, subProtocol);
		}
	}
}
