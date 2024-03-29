// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.json.JSONObject;

import com.microsoft.azure.relay.ListenerCommand.*;

public class HybridConnectionListener implements RelayTraceSource, AutoCloseable {
	static final AutoShutdownScheduledExecutor EXECUTOR = AutoShutdownScheduledExecutor.Create();
	private final InputQueue<HybridConnectionChannel> connectionInputQueue;
	private final ControlConnection controlConnection;
	private final Object thisLock = new Object();
	private final AtomicBoolean openCalled;
	private final AtomicBoolean closeCalled;
	private Duration operationTimeout;
	private int maxWebSocketBufferSize;
	private String cachedString;
	private Function<RelayedHttpListenerContext, Boolean> acceptHandler;
	private Consumer<RelayedHttpListenerContext> requestHandler;
	private URI address;
	private TrackingContext trackingContext;
	private TokenProvider tokenProvider;
	private Throwable injectedFault;
	private Consumer<Throwable> connectingHandler;
	private Consumer<Throwable> offlineHandler;
	private Runnable onlineHandler;

	/**
	 * Create a new HybridConnectionListener instance for accepting
	 * HybridConnections.
	 * 
	 * @param address       The address on which to listen for HybridConnections.
	 *                      This address should be of the format
	 *                      "sb://contoso.servicebus.windows.net/yourhybridconnection".
	 * @param tokenProvider The TokenProvider for connecting this listener to
	 *                      ServiceBus.
	 */
	public HybridConnectionListener(URI address, TokenProvider tokenProvider) {

		if (address == null || tokenProvider == null) {
			throw RelayLogger.argumentNull("address or tokenProvider", this);
		} else if (!address.getScheme().equals(RelayConstants.HYBRID_CONNECTION_SCHEME)) {
			throw RelayLogger.throwingException(
					new IllegalArgumentException("Invalid scheme. Expected: " + RelayConstants.HYBRID_CONNECTION_SCHEME + ", Actual: " + address.getScheme() + "."), this);
		}

		this.address = address;
		this.tokenProvider = tokenProvider;
		this.operationTimeout = RelayConstants.DEFAULT_OPERATION_TIMEOUT;
		this.trackingContext = TrackingContext.create(this.address);
		this.connectionInputQueue = new InputQueue<HybridConnectionChannel>(EXECUTOR);
		this.controlConnection = new ControlConnection(this);
		this.openCalled = new AtomicBoolean(false);
		this.closeCalled = new AtomicBoolean(false);
	}

	/**
	 * Create a new HybridConnectionListener instance for accepting
	 * HybridConnections.
	 * 
	 * @param connectionString The connection string to use. This connection string
	 *                         must include the EntityPath property.
	 * @throws URISyntaxException Thrown when the format of the connectionSring is
	 *                            incorrect
	 */
	public HybridConnectionListener(String connectionString) throws URISyntaxException {
		this(connectionString, null, true);
	}

	/**
	 * Creates a new instance of HybridConnectionListener from a connection string
	 * and the specified HybridConection path. Use this overload only when the
	 * connection string does not use the RelayConnectionStringBuilder.EntityPath
	 * property.
	 * 
	 * @param connectionString The connection string to use. This connection string
	 *                         must not include the EntityPath property.
	 * @param path             The path to the HybridConnection.
	 * @throws URISyntaxException Thrown when the format of the connectionSring is
	 *                            incorrect
	 */
	public HybridConnectionListener(String connectionString, String path) throws URISyntaxException {
		this(connectionString, path, false);
	}

	/**
	 * This private .ctor handles both of the public overloads which take
	 * connectionString
	 * 
	 * @param connectionString         The connection String used. This connection
	 *                                 string must not include the EntityPath
	 *                                 property.
	 * @param path                     path The path to the HybridConnection.
	 * @param pathFromConnectionString True if path is implicitly defined in the
	 *                                 connection string
	 * @throws URISyntaxException Thrown when the format of the connectionSring is
	 *                            incorrect
	 */
	HybridConnectionListener(String connectionString, String path, boolean pathFromConnectionString)
			throws URISyntaxException {
		if (StringUtil.isNullOrWhiteSpace(connectionString)) {
			throw RelayLogger.argumentNull("connectionString", this);
		}

		RelayConnectionStringBuilder builder = new RelayConnectionStringBuilder(connectionString);
		builder.validate();

		if (pathFromConnectionString) {
			if (StringUtil.isNullOrWhiteSpace(builder.getEntityPath())) {
				throw RelayLogger.argumentNull("entityPath", this);
			}
		} else {
			if (StringUtil.isNullOrWhiteSpace(path)) {
				throw RelayLogger.argumentNull("path", this);
			} else if (!StringUtil.isNullOrWhiteSpace(builder.getEntityPath())) {
				throw RelayLogger.throwingException(
					new IllegalArgumentException("EntityPath must not appear in connectionString"), this);
			}

			builder.setEntityPath(path);
		}

		this.address = new URI(builder.getEndpoint() + builder.getEntityPath());
		this.tokenProvider = builder.createTokenProvider();
		this.operationTimeout = builder.getOperationTimeout();
		this.trackingContext = TrackingContext.create(this.address);
		this.connectionInputQueue = new InputQueue<HybridConnectionChannel>(EXECUTOR);
		this.controlConnection = new ControlConnection(this);
		this.openCalled = new AtomicBoolean(false);
		this.closeCalled = new AtomicBoolean(false);
	}
	
	public boolean isOnline() {
		return this.controlConnection.isOnline();
	}

	public Function<RelayedHttpListenerContext, Boolean> getAcceptHandler() {
		return acceptHandler;
	}

	/**
	 * Installing a custom handler which can inspect request headers, control response headers, 
	 * decide whether to accept or reject a websocket upgrade request, and control the status code/description if rejecting.
	 * If choosing to reject the websocket connection request, the response code and description can be set through the
	 * response object of the RelayedHttpListenerContext instance provided.
	 * 
	 * @param acceptHandler A Function which takes in the websocket connection request context
	 * 						and returns true/false if the request should be accepted/rejected
	 */
	public void setAcceptHandler(Function<RelayedHttpListenerContext, Boolean> acceptHandler) {
		this.acceptHandler = acceptHandler;
	}

	public Consumer<RelayedHttpListenerContext> getRequestHandler() {
		return requestHandler;
	}

	/**
	 * Install a custom handler which will be run upon receiving a HTTP request. 
	 * The corresponding HTTP response can be set through the response object of the given context instance.
	 * 
	 * @param requestHandler A Consumer which takes in the incoming HTTP request context
	 */
	public void setRequestHandler(Consumer<RelayedHttpListenerContext> requestHandler) {
		this.requestHandler = requestHandler;
	}

	/**
	 * The address on which to listen for HybridConnections. This address should be
	 * of the format "sb://contoso.servicebus.windows.net/yourhybridconnection".
	 */
	public URI getAddress() {
		return this.address;
	}

	/**
	 * The TokenProvider for authenticating this HybridConnection listener.
	 */
	public TokenProvider getTokenProvider() {
		return this.tokenProvider;
	}

	/**
	 * The TrackingContext for this listener.
	 */
	public TrackingContext getTrackingContext() {
		return this.trackingContext;
	}

	public Duration getOperationTimeout() {
		return this.operationTimeout;
	}

	public int getMaxWebSocketBufferSize() {
		return maxWebSocketBufferSize;
	}

	public void setMaxWebSocketBufferSize(int maxWebSocketBufferSize) {
		if (maxWebSocketBufferSize > 0) {
			this.maxWebSocketBufferSize = maxWebSocketBufferSize;
		} else {
			RelayLogger.logEvent("objectNotSet", this, "maxWebSocketBufferSize");
		}
	}

	/**
	 * Returns the handler that will be run when the listener disconnects unexpectedly.
	 * The listener will attempt to reconnect after this handler runs.	
	 */
	public Consumer<Throwable> getConnectingHandler() {
		return connectingHandler;
	}

	/**
	 * Sets the handler that will be run when the listener disconnects unexpectedly.
	 * The listener will attempt to reconnect after this handler runs.	
	 */
	public void setConnectingHandler(Consumer<Throwable> onConnecting) {
		this.connectingHandler = onConnecting;
	}

	/**
	 * Returns the handler which will be run after the listener has become offline.
	 * Reconnection will not be attempted after this handler.
	 */
	public Consumer<Throwable> getOfflineHandler() {
		return offlineHandler;
	}

	/**
	 * Sets the handler which will be run after the listener has become offline.
	 * Reconnection will not be attempted after this handler.
	 */
	public void setOfflineHandler(Consumer<Throwable> onOffline) {
		this.offlineHandler = onOffline;
	}

	/**
	 * Returns the handler that will be run after the listener has established connection to the cloud service.
	 */
	public Runnable getOnlineHandler() {
		return onlineHandler;
	}

	/**
	 * Sets the handler that will be run after the listener has established connection to the cloud service.
	 */
	public void setOnlineHandler(Runnable onOnline) {
		this.onlineHandler = onOnline;
	}

	/**
	 * Opens the HybridConnectionListener and registers it as a listener in
	 * ServiceBus.
	 * 
	 * @return A CompletableFuture which completes when the control connection is
	 *         established with the cloud service
	 */
	public CompletableFuture<Void> openAsync() {
		return this.openAsync(this.operationTimeout);
	}

	/**
	 * Opens the HybridConnectionListener and registers it as a listener in
	 * ServiceBus.
	 * 
	 * @param timeout The timeout duration for this openAsync operation
	 * @return A CompletableFuture which completes when the control connection is
	 *         established with the cloud service
	 */
	public CompletableFuture<Void> openAsync(Duration timeout) {
		TimeoutHelper.throwIfNegativeArgument(timeout);

		synchronized (this.thisLock) {
			try {
				this.throwIfDisposed();
				this.throwIfReadOnly();
			} catch (RelayException e) {
				return CompletableFutureUtil.fromException(e);
			}
			this.openCalled.set(true);
		}

		return this.controlConnection.openAsync(timeout);
	}

	/**
	 * Disconnects all connections from the cloud service
	 * 
	 * @return A CompletableFuture which completes when all connections are
	 *         disconnected with the cloud service
	 */
	public CompletableFuture<Void> closeAsync() {
		return this.closeAsync(this.operationTimeout);
	}

	/**
	 * Disconnects all connections from the cloud service within the timeout
	 * 
	 * @param timeout The timeout duration for this closeAsync operation
	 * @return A CompletableFuture which completes when all connections are
	 *         disconnected with the cloud service
	 */
	public CompletableFuture<Void> closeAsync(Duration timeout) {
		TimeoutHelper timeoutHelper = new TimeoutHelper(timeout);
		CompletableFuture<?>[] closeTasks;
		synchronized (this.thisLock) {
			if (this.closeCalled.get()) {
				return CompletableFuture.completedFuture(null);
			}

			RelayLogger.logEvent("closing", this);
			this.closeCalled.set(true);

			// If the input queue is empty this completes all pending waiters with null and
			// prevents any new items being added to the input queue.
			this.connectionInputQueue.shutdown();

			// Close any unaccepted rendezvous. DequeueAsync won't block since we've called
			// connectionInputQueue.Shutdown().
			closeTasks = new CompletableFuture<?>[this.connectionInputQueue.getPendingCount()];
			for (int i = 0; i < this.connectionInputQueue.getPendingCount(); i++) {
				closeTasks[i] = this.connectionInputQueue.dequeueAsync(timeoutHelper.remainingTime()).thenAccept(connection -> {
					connection.closeAsync(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Client closing the socket normally"));
				});
			}
		}
		
		return CompletableFuture.allOf(closeTasks)
			.thenCompose($void -> this.controlConnection.closeAsync(timeoutHelper.remainingTime()))
			.whenComplete(($void, ex) -> {
				this.connectionInputQueue.dispose();
				RelayLogger.logEvent("closed", this);
				if (ex != null) {
					throw RelayLogger.throwingException(ex, this);
				}
			});
	}

	@Override
	public void close() {
		this.closeAsync().join();
	}
	
	/**
	 * Asynchronously wait for a websocket connection from the sender to be connected. When the listener closes, 
	 * all pending CompletableFutures that are still waiting to accept a connection will complete with null.
	 * 
	 * @return A CompletableFuture which completes when a websocket connection from the sender is established.
	 */
	public CompletableFuture<HybridConnectionChannel> acceptConnectionAsync() {
	    CompletableFuture<HybridConnectionChannel> connection = null;
		synchronized (this.thisLock) {
			if (!this.openCalled.get()) {
				throw RelayLogger.invalidOperation("cannot accept connection because listener is not open.", this);
			}
			
			connection = this.connectionInputQueue.dequeueAsync();
		}
		return connection;
	}

	@Override
	public String toString() {
		if (this.cachedString == null) {
			this.cachedString = this.getClass().getSimpleName() + "(" + this.trackingContext + ")";
		}
		return this.cachedString;
    }

	CompletableFuture<Void> sendControlCommandAndStreamAsync(ListenerCommand command, ByteBuffer buffer, Duration timeout) {
		return this.controlConnection.sendCommandAndStreamAsync(command, buffer, timeout);
	}

	void throwIfDisposed() throws RelayException {
		if (this.closeCalled.get()) {
			throw RelayLogger.invalidOperation("Invalid operation. Cannot call open when it's already closed.", this);
		}
	}

	void throwIfReadOnly() throws RelayException {
		if (this.openCalled.get()) {
			throw RelayLogger.invalidOperation("Invalid operation. Cannot call open when it's already open.", this);
		}
	}

	private CompletableFuture<Void> onCommandAsync(String message, ClientWebSocket controlWebSocket) throws URISyntaxException, UnsupportedEncodingException {
	    JSONObject jsonObj = new JSONObject(message);
	    ListenerCommand listenerCommand = new ListenerCommand(jsonObj);
	    AcceptCommand accept = listenerCommand.getAccept();
	    RequestCommand request = listenerCommand.getRequest();

		if (accept != null) {
	         // Don't block the pump waiting for the rendezvous
			return CompletableFuture.completedFuture(accept).thenComposeAsync(acceptCommand -> {
			    return this.onAcceptCommandAsync(acceptCommand);
			}, EXECUTOR);
		} else if (request != null) {
			return HybridHttpConnection.createAsync(this, request, controlWebSocket);
		} else {
			return CompletableFutureUtil.fromException(new IllegalArgumentException("Invalid HybridConnection command was received."));
		}
	}

	private CompletableFuture<Void> onAcceptCommandAsync(ListenerCommand.AcceptCommand acceptCommand) {
		try {
			URI rendezvousUri = new URI(acceptCommand.getAddress());
			URI requestUri = this.generateAcceptRequestUri(rendezvousUri);

			RelayedHttpListenerContext listenerContext = new RelayedHttpListenerContext(this, requestUri,
					acceptCommand.getId(), "GET", acceptCommand.getConnectHeaders());
			listenerContext.getRequest().setRemoteEndPoint(acceptCommand.getRemoteEndpoint());

			Function<RelayedHttpListenerContext, Boolean> acceptHandler = this.acceptHandler;

			boolean shouldAccept = acceptHandler == null;

			RelayLogger.logEvent("rendezvousStart", this, acceptCommand.getAddress());
			
			if (acceptHandler != null) {
				// Invoke and await the user's AcceptHandler method
				try {
					shouldAccept = acceptHandler.apply(listenerContext);
				} catch (Exception userException) {
                    listenerContext.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY_502);
                    listenerContext.getResponse().setStatusDescription("The Listener's custom AcceptHandler threw an exception. See Listener logs for details. TrackingId: " + listenerContext.getTrackingContext().getTrackingId());
					throw RelayLogger.throwingException(userException, this);
				}
			}

			return this.completeAcceptAsync(listenerContext, rendezvousUri, shouldAccept);
		} catch (Exception exception) {
			RelayLogger.logEvent("rendezvousFailed", this, exception.toString());
			RelayLogger.logEvent("rendezVousStop", this);
			return CompletableFutureUtil.fromException(exception);
		}
	}

	/**
	/* Form the logical request Uri using the scheme://host:port from the listener
	/* and the path from the acceptCommand (minus "/$hc")
	/* e.g. sb://contoso.servicebus.windows.net/hybrid1?foo=bar
	**/
	private URI generateAcceptRequestUri(URI rendezvousUri) throws URISyntaxException, UnsupportedEncodingException {
		String query = HybridConnectionUtil.filterQueryString(rendezvousUri.getQuery());
		String path = rendezvousUri.getPath();
		path = (path.startsWith("$hc/")) ? path.substring(4) : path;

		URI address = this.address;
		return new URI(address.getScheme(), address.getUserInfo(), address.getHost(), address.getPort(), path, query,
				address.getFragment());
	}

	private CompletableFuture<Void> completeAcceptAsync(RelayedHttpListenerContext listenerContext, URI rendezvousUri, boolean shouldAccept) {
		CompletableFuture<Void> completeAcceptTask = new CompletableFuture<Void>();
		
		if (shouldAccept) {
			synchronized (this.thisLock) {
				WebSocketChannel rendezvousConnection = new WebSocketChannel(listenerContext.getTrackingContext(), EXECUTOR);

				if (this.closeCalled.get()) {
					RelayLogger.logEvent("rendezvousClose", this, rendezvousUri.toString());
					completeAcceptTask = CompletableFuture.completedFuture(null);
				} else {
					completeAcceptTask = rendezvousConnection.getWebSocket().connectAsync(rendezvousUri).thenRun(() -> 
						this.connectionInputQueue.enqueueAndDispatch(rendezvousConnection, null, false));
				}
			}
		} else {
			RelayLogger.logEvent("rendezvousRejected", 
					this, 
					String.valueOf(listenerContext.getResponse().getStatusCode()), 
					listenerContext.getResponse().getStatusDescription());
			completeAcceptTask = listenerContext.rejectAsync(rendezvousUri);
		}
		
		return completeAcceptTask.whenComplete((result, ex) -> {
			if (ex != null) {
				throw RelayLogger.throwingException(ex, this);
			}
			RelayLogger.logEvent("rendezvousStop", this);
		});
	}
	
	void clearFault() {
        this.injectedFault = null;
	}
	
	/**
	 * Inject a specific exception to the listener or its underlying websocket for testing purposes
	 * @param throwable The following exceptions can be interpreted:
	 * </br><b>UpgradeException</b>    - When this exception is injected, all listener websocket connection attempts will fail 
	 *                                   until the fault has been cleared.
	 * </br><b>ConnectionException</b> - Injecting this exception will kill the listener's websocket (simulate a network disconnect),
	 *                                   but should cause the listener to try to reconnect.
	 */
	void injectFault(Throwable throwable) {
	    if (throwable == null) {
	        return;
	    }
	    
	    if (throwable instanceof UpgradeException) {
	        this.injectedFault = throwable;
	    }
	    
	    if (throwable instanceof ConnectionLostException) {
	        CompletableFuture<ClientWebSocket> controlConnectionTask = this.controlConnection.connectAsyncTask;
	        if (controlConnectionTask != null) {
	            controlConnectionTask.thenAccept(webSocket -> {
	                webSocket.dispose();
	            });
	        }
	    }
	}

	/**
	 * Connects, maintains, and transparently reconnects this listener's control
	 * connection with the cloud service.
	 */
	final class ControlConnection implements AutoCloseable {
		private final HybridConnectionListener listener;
		private final URI address;
		@SuppressWarnings("unused")
		private String path;
		private final TokenRenewer tokenRenewer;
		private final AsyncLock sendAsyncLock;
		private final Object thisLock = new Object();
		private CompletableFuture<ClientWebSocket> connectAsyncTask;
		private AtomicInteger connectDelayIndex;
	    private AtomicBoolean closeCalled;
		private Throwable lastError;


		ControlConnection(HybridConnectionListener listener) {
			this.listener = listener;
			this.address = listener.address;
			String rawPath = this.address.getPath();
			this.path = (rawPath.startsWith("/")) ? rawPath.substring(1) : rawPath;
			this.sendAsyncLock = new AsyncLock(EXECUTOR);
			this.connectDelayIndex = new AtomicInteger(0);
			this.closeCalled = new AtomicBoolean(false);
			this.tokenRenewer = new TokenRenewer(
				this.listener, this.address.toString(),	TokenProvider.DEFAULT_TOKEN_TIMEOUT);
		}
		
		boolean isOnline() {
			synchronized (this.thisLock) {
				return CompletableFutureUtil.isDoneNormally(connectAsyncTask) && connectAsyncTask.join().isOpen();
			}
		}

		public Throwable getLastError() {
			return lastError;
		}
		
		/**
		 * Establish websocket connection between the control websocket and the cloud
		 * service, then start receiving command messages
		 * 
		 * @param timeout The timeout to connect to cloud service within
		 * @return Returns a completableFuture which completes when websocket connection
		 *         is established between the control websocket and the cloud service
		 */
		public CompletableFuture<Void> openAsync(Duration timeout) {
			// Establish a WebSocket connection right now so we can detect any fatal errors		
			CompletableFuture<ClientWebSocket> connectTask = this.ensureConnectTask(timeout);
			return connectTask.thenAccept((webSocket) -> {
					this.tokenRenewer.setOnTokenRenewed((token) -> this.onTokenRenewed(token));
					this.receivePumpAsync(); // This starts pumping but doesn't wait
				}).whenComplete(($void, err) -> {
					if (err != null) {
						RelayLogger.throwingException(err, this.listener);
						CloseReason closeReason = new CloseReason(CloseCodes.UNEXPECTED_CONDITION,
								"closing web socket connection because something went wrong trying to connect.");
						this.closeOrAbortWebSocketAsync(connectTask, closeReason);
						throw new CompletionException(err);
					}
				});
		}

		/**
		 * Ensures connection of the control websocket, then disconnects it from the cloud service
		 * 
		 * @param timeout The timeout to disconnect to cloud service within
		 * @return Returns a completableFuture which completes when websocket connection
		 *         is established between the control websocket and the cloud service
		 */
		private CompletableFuture<Void> closeAsync(Duration duration) {
			
			CompletableFuture<ClientWebSocket> connectTask;
			synchronized (this.thisLock) {
				if (this.closeCalled.get()) {
					return CompletableFuture.completedFuture(null);
				}
				this.closeCalled.set(true);
				connectTask = this.connectAsyncTask;
				this.connectAsyncTask = null;
			}
			
			this.tokenRenewer.close();
			if (connectTask != null) {
				return connectTask.thenCompose((webSocket) -> {
					return this.sendAsyncLock.acquireThenCompose(duration, () -> {
						CloseReason reason = new CloseReason(CloseCodes.NORMAL_CLOSURE, "Normal Closure");					
						return webSocket.closeAsync(reason);
					});
				});
			}
			
			// TODO: Wait for ReceivePump to complete?			
			return CompletableFuture.completedFuture(null);
		}

		/**
		 * Sends the command through the control websocket, along with the message body
		 * if it exists
		 * 
		 * @param command The Listener command to be sent
		 * @param buffer  The message body to be sent, null if it doesn't exist
		 * @param timeout The timeout to send within
		 * @return Returns a completableFuture which completes when the command and
		 *         stream are finished sending
		 */
		private CompletableFuture<Void> sendCommandAndStreamAsync(ListenerCommand command, ByteBuffer buffer, Duration timeout) {

			return this.ensureConnectTask(timeout)
				.thenCompose(webSocket -> {
					return sendAsyncLock.acquireThenCompose(timeout, () -> {
						String json = null;
						if (command.getResponse() != null) {
							json = command.getResponse().toJsonString();
						}
						else if (command.getRenewToken() != null) {
							json = command.getRenewToken().toJsonString();
						}

						if (json != null) {
							RelayLogger.logEvent("sendCommand", this, json);
							return webSocket.writeAsync(json, timeout, true, WriteMode.TEXT)
								.thenCompose($void -> {
									if (buffer != null) {
										return webSocket.writeAsync(buffer);
									} else {
										return CompletableFuture.completedFuture(null);
									}
								});
						} else {
							return CompletableFutureUtil.fromException(new IllegalArgumentException("Invalid command to be sent by the listener to the cloud service"));
						}
					});
				});
		}

		/**
		 * Establish websocket connection between the control websocket and the cloud
		 * service if not already established.
		 * 
		 * @param timeout The timeout to connect to cloud service within
		 * @return Returns a completableFuture which completes when websocket connection
		 *         is established between the control websocket and the cloud service
		 * @throws InterruptedException Throws when interrupted during the connection
		 *                              delay
		 */
		private CompletableFuture<ClientWebSocket> ensureConnectTask(Duration timeout) {
			synchronized (this.thisLock) {
				if (this.connectAsyncTask == null || !this.isOnline()) {
					this.connectAsyncTask = this.connectAsync(timeout);
				}
				return this.connectAsyncTask;
			}
		}

		/**
		 * Establish websocket connection between the control websocket and the cloud
		 * service.
		 * 
		 * @param timeout The timeout to connect to cloud service within
		 * @return Returns a completableFuture which completes when websocket connection
		 *         is established between the control websocket and the cloud service
		 * @throws InvalidRelayOperationException 
		 * @throws InterruptedException Throws when interrupted during the connection
		 *                              delay
		 */
		private CompletableFuture<ClientWebSocket> connectAsync(Duration timeout) {
			try {
				this.listener.throwIfDisposed();
				
				CompletableFuture<Void> delayTask = CompletableFutureUtil.delayAsync(RelayConstants.CONNECTION_DELAY_INTERVALS[this.connectDelayIndex.get()], EXECUTOR);
				CompletableFuture<SecurityToken> token = this.tokenRenewer.getTokenAsync();

				// Set the authentication in request header
				Map<String, List<String>> headers = new HashMap<String, List<String>>();
				headers.put(RelayConstants.SERVICEBUS_AUTHORIZATION_HEADER_NAME, Arrays.asList(token.join().getToken()));
				HybridConnectionEndpointConfigurator configurator = new HybridConnectionEndpointConfigurator();
				configurator.addHeaders(headers);
				ClientEndpointConfig config = ClientEndpointConfig.Builder.create().configurator(configurator).build();

				// When we reconnect we need to remove the "_GXX" suffix otherwise trackingId
				// gets longer after each reconnect
				String trackingId = TrackingContext.removeSuffix(this.listener.trackingContext.getTrackingId());

				// Build the websocket uri, e.g.
				// "wss://contoso.servicebus.windows.net:443/$hc/endpoint1?sb-hc-action=listen&sb-hc-id=E2E_TRACKING_ID"
				URI websocketUri = HybridConnectionUtil.buildUri(this.address.getHost(), this.address.getPort(),
							this.address.getPath(), this.address.getQuery(), HybridConnectionConstants.Actions.LISTEN,
							trackingId);

				ClientWebSocket webSocket = new ClientWebSocket(this.listener.trackingContext, EXECUTOR);
				return delayTask.thenCompose(($void) -> {
	                if (this.listener.injectedFault != null && this.listener.injectedFault instanceof UpgradeException) {
	                    return CompletableFutureUtil.fromException(this.listener.injectedFault);
	                }
	                
					return webSocket.connectAsync(websocketUri, timeout, config)
						.thenApply(($void2) -> {
							this.onOnline();
							return webSocket;
						});
				});
			}
			catch (Throwable e) {
				return CompletableFutureUtil.fromException(e);
			}
		}
		
		private CompletableFuture<Void> closeOrAbortWebSocketAsync(CompletableFuture<ClientWebSocket> connectTask, CloseReason reason) {
			assert CompletableFutureUtil.isDoneNormally(connectTask);
			
			synchronized (this.thisLock) {
				if (connectTask == this.connectAsyncTask) {
					this.connectAsyncTask = null;
				}
			}
			
			return connectTask.thenCompose((webSocket) -> webSocket.closeAsync(reason))
				.exceptionally((exception) -> {
					// catch and do not rethrow
					RelayLogger.handledExceptionAsWarning(exception, this.listener);
					return null;
				});
		}
		
		@Override
		public void close() {
			this.closeAsync(RelayConstants.DEFAULT_OPERATION_TIMEOUT).join();
		}

		/**
		 * Ensure we have a connected control webSocket, listens for command messages,
		 * and handles those messages.
		 * 
		 * @return A boolean indicating whether or not the receive pump should keep
		 *         running.
		 * @throws InterruptedException
		 */
		private CompletableFuture<Void> receivePumpAsync() {
			return receivePumpCoreAsync().handle((keepGoing, ex) -> {
				if (keepGoing) {				
					receivePumpAsync();
				} else {
					if (ex != null) {
						RelayLogger.throwingException(ex, this, TraceLevel.WARNING);
					}					
					this.onOffline(ex);
				}

				return null;
			});
		}
		
		/**
		 * Ensure we have a connected control webSocket, listens for command messages,
		 * and handles those messages.
		 * 
		 * @return A CompletableFuture boolean which completes when the control
		 *         websocket is disconnected and indicates whether or not the receive
		 *         pump should keep running.
		 */
		private CompletableFuture<Boolean> receivePumpCoreAsync() {
			CompletableFuture<ClientWebSocket> connectTask = this.ensureConnectTask(null);
			return connectTask.handle((webSocket, ex) -> {
			    if (ex != null) {
			        this.lastError = ex;
			        return null;
			    }
			    return webSocket;
			}).thenCompose(webSocket -> {
			    if (webSocket == null || !webSocket.isOpen()) {
			        return CompletableFuture.completedFuture(this.onDisconnect(this.lastError));
			    }
			    
				return webSocket.readTextAsync()
					.thenApply(receivedMessage -> {
						boolean keepGoing = true;
						try {
							if (!webSocket.isOpen()) {
								this.closeOrAbortWebSocketAsync(connectTask, webSocket.getCloseReason());
								if (this.closeCalled.get()) {
									keepGoing = false;
								} 
								else {
									CloseReason reason = webSocket.getCloseReason();
									keepGoing = this.onDisconnect(new ConnectionLostException(reason.toString()));
								}
								return keepGoing;
							}
							if (receivedMessage != null) {
								this.listener.onCommandAsync(receivedMessage, webSocket);
							}
						} catch (Exception exception) {
							RelayLogger.handledExceptionAsWarning(exception, this.listener);
							this.closeOrAbortWebSocketAsync(connectTask, null);
							keepGoing = this.onDisconnect(exception);
						}
						return keepGoing;
				});
			});
		}

		private void onOnline() {
			synchronized (this.thisLock) {
				if (this.isOnline()) {
					return;
				}

				this.lastError = null;
				this.connectDelayIndex.set(-1);
			}
			RelayLogger.logEvent("connected", this.listener);
			
			Runnable onlineHandler = this.listener.getOnlineHandler();
			if (onlineHandler != null) {
				onlineHandler.run();
			}
		}

		private void onOffline(Throwable lastError) {
			if (lastError != null) {
				this.lastError = lastError;
			}
			RelayLogger.logEvent("offline", this);
			
			Consumer<Throwable> offlineHandler = this.listener.getOfflineHandler();
			if (offlineHandler != null) {
				offlineHandler.accept(lastError);
			}
		}

		// Returns true if this control connection should attempt to reconnect after this exception.
		private boolean onDisconnect(Throwable lastError) {

			synchronized (this.thisLock) {
				this.lastError = lastError;

				this.connectDelayIndex.updateAndGet((index) -> {
				    return index < RelayConstants.CONNECTION_DELAY_INTERVALS.length - 1 ? ++index : index;
				});
			}

			// Inspect the close status/description to see if this is a terminal case
			// or we should attempt to reconnect.
			boolean shouldReconnect = this.shouldReconnect(lastError);
			RelayLogger.logEvent("disconnect", this, String.valueOf(shouldReconnect));

			if (shouldReconnect) {
				Consumer<Throwable> connectingHandler = this.listener.getConnectingHandler();
				if (connectingHandler != null) {
					connectingHandler.accept(lastError);
				}
			}

			return shouldReconnect;
		}

		private boolean shouldReconnect(Throwable exception) {
			return (!(exception instanceof EndpointNotFoundException));
		}

		private void onTokenRenewed(SecurityToken token) {
			ListenerCommand listenerCommand = new ListenerCommand(null);
			listenerCommand.setRenewToken(listenerCommand.new RenewTokenCommand(null));
			listenerCommand.getRenewToken().setToken(token.toString());

			this.sendCommandAndStreamAsync(listenerCommand, null, null).exceptionally((ex) -> {
				RelayLogger.throwingException(ex, this.listener, TraceLevel.WARNING);
				return null;
			});
		}
	}
}
