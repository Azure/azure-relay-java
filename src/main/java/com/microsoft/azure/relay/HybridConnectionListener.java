package com.microsoft.azure.relay;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;

import org.eclipse.jetty.http.HttpStatus;
import org.json.JSONObject;

public class HybridConnectionListener implements RelayTraceSource, AutoCloseable {
	static final AutoShutdownScheduledExecutor EXECUTOR = AutoShutdownScheduledExecutor.Create();
	private final InputQueue<HybridConnectionChannel> connectionInputQueue;
	private final ControlConnection controlConnection;
	private final Object thisLock = new Object();
	private boolean openCalled;
	private volatile boolean closeCalled;
	private Duration operationTimeout;
	private int maxWebSocketBufferSize;
	private String cachedString;

	/**
	 * Gets a value that determines whether the connection is online. True if the
	 * connection is alive and online; False if there is no connectivity towards the
	 * Azure Service Bus from the current network location.
	 */
	private boolean isOnline;

	/**
	 * Allows installing a custom handler which can inspect request headers, control
	 * response headers, decide whether to accept or reject a web-socket upgrade
	 * request, and control the status code/description if rejecting. The
	 * AcceptHandler should return true to accept a client request or false to
	 * reject.
	 */
	private Function<RelayedHttpListenerContext, Boolean> acceptHandler;

	/**
	 * A handler to run upon receiving Hybrid Http Requests.
	 */
	private Consumer<RelayedHttpListenerContext> requestHandler;

	/**
	 * The address on which to listen for HybridConnections. This address should be
	 * of the format "sb://contoso.servicebus.windows.net/yourhybridconnection".
	 */
	private URI address;

	/**
	 * The TrackingContext for this listener.
	 */
	private TrackingContext trackingContext;

	/**
	 * The TokenProvider for authenticating this HybridConnection listener.
	 */
	private TokenProvider tokenProvider;

	public boolean isOnline() {
		return this.isOnline;
	}

	public Function<RelayedHttpListenerContext, Boolean> getAcceptHandler() {
		return acceptHandler;
	}

	public void setAcceptHandler(Function<RelayedHttpListenerContext, Boolean> acceptHandler) {
		this.acceptHandler = acceptHandler;
	}

	public Consumer<RelayedHttpListenerContext> getRequestHandler() {
		return requestHandler;
	}

	public void setRequestHandler(Consumer<RelayedHttpListenerContext> requestHandler) {
		this.requestHandler = requestHandler;
	}

	public URI getAddress() {
		return this.address;
	}

	public TokenProvider getTokenProvider() {
		return this.tokenProvider;
	}

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
	}

	/**
	 * Opens the HybridConnectionListener and registers it as a listener in
	 * ServiceBus.
	 * 
	 * @return A CompletableFuture which completes when the control connection is
	 *         established with the cloud service
	 * @throws InvalidRelayOperationException 
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
			this.openCalled = true;
		}

		return this.controlConnection.openAsync(timeout).thenRun(() -> {
			synchronized (this.thisLock) {
				this.isOnline = true;
			}
		});
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
		try {
			CompletableFutureUtil.timedRunAsync(timeout, () -> {
					List<HybridConnectionChannel> clients;
					synchronized (this.thisLock) {
						if (this.closeCalled) {
							return;
						}
	
						RelayLogger.logEvent("closing", this);
						this.closeCalled = true;
	
						// If the input queue is empty this completes all pending waiters with null and
						// prevents any new items being added to the input queue.
						this.connectionInputQueue.shutdown();
	
						// Close any unaccepted rendezvous. DequeueAsync won't block since we've called
						// connectionInputQueue.Shutdown().
						clients = new ArrayList<HybridConnectionChannel>(this.connectionInputQueue.getPendingCount());
						HybridConnectionChannel webSocket;
						do {
							webSocket = this.connectionInputQueue.dequeueAsync().join();
	
							if (webSocket != null) {
								clients.add(webSocket);
							}
						} while (webSocket != null);
	
						this.isOnline = false;
					}
	
					clients.forEach(client -> client.closeAsync(
							new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Client closing the socket normally")));
	
					RelayLogger.logEvent("closed", this);
				},
				EXECUTOR).join();
		}
		catch (Exception e) {
			throw RelayLogger.throwingException(e, this);
		} finally {
			this.connectionInputQueue.dispose();
		}
		return this.controlConnection.closeAsync(null);
	}

	@Override
	public void close() {
		this.closeAsync().join();
	}
	
	/**
	 * Asynchronously wait for a websocket connection from the sender to be
	 * connected
	 * 
	 * @return A CompletableFuture which completes when aa websocket connection from
	 *         the sender is connected
	 * @throws InvalidRelayOperationException 
	 */
	public CompletableFuture<HybridConnectionChannel> acceptConnectionAsync() {
		synchronized (this.thisLock) {
			if (!this.openCalled) {
				throw RelayLogger.invalidOperation("cannot accept connection because listener is not open.", this);
			}
		}
		return this.connectionInputQueue.dequeueAsync();
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
		if (this.closeCalled) {
			throw RelayLogger.invalidOperation("Invalid operation. Cannot call open when it's already closed.", this);
		}
	}

	void throwIfReadOnly() throws RelayException {
		synchronized (this.thisLock) {
			if (this.openCalled) {
				throw RelayLogger.invalidOperation("Invalid operation. Cannot call open when it's already open.", this);
			}
		}
	}

	private CompletableFuture<Void> onCommandAsync(String message, ClientWebSocket controlWebSocket) throws URISyntaxException, UnsupportedEncodingException {
		return CompletableFuture.supplyAsync(() -> {
			JSONObject jsonObj = new JSONObject(message);
			return new ListenerCommand(jsonObj);
		}).thenCompose(listenerCommand -> {
			if (listenerCommand.getAccept() != null) {
				return this.onAcceptCommandAsync(listenerCommand.getAccept());
			} else if (listenerCommand.getRequest() != null) {
				return HybridHttpConnection.createAsync(this, listenerCommand.getRequest(), controlWebSocket);
			} else {
				return CompletableFutureUtil.fromException(new IllegalArgumentException("Invalid HybridConnection command was received."));
			}
		});
	}

	private CompletableFuture<Void> onAcceptCommandAsync(ListenerCommand.AcceptCommand acceptCommand) {
		try {
			URI rendezvousUri = new URI(acceptCommand.getAddress());
			URI requestUri = this.generateAcceptRequestUri(rendezvousUri);

			RelayedHttpListenerContext listenerContext = new RelayedHttpListenerContext(this, requestUri,
					acceptCommand.getId(), "GET", acceptCommand.getConnectHeaders());
			listenerContext.getRequest().setRemoteAddress(acceptCommand.getRemoteEndpoint());

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
					throw userException;
				}
			}

			// Don't block the pump waiting for the rendezvous
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

				if (this.closeCalled) {
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

	/**
	 * Connects, maintains, and transparently reconnects this listener's control
	 * connection with the cloud service.
	 */
	final class ControlConnection implements AutoCloseable {
		// Retries after 0, 1, 2, 5, 10, 30 seconds
		final Duration[] CONNECTION_DELAY_INTERVALS = { Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2),
				Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30) };
		private final HybridConnectionListener listener;
		private final URI address;
		@SuppressWarnings("unused")
		private String path;
		private final TokenRenewer tokenRenewer;
		private final AsyncLock sendAsyncLock;
		private CompletableFuture<Void> connectAsyncTask;
		private int connectDelayIndex;
		private boolean isOnline;
		private Throwable lastError;
		private BiConsumer<Object, Object[]> connectingHandler;
		private BiConsumer<Object, Object[]> offlineHandler;
		private BiConsumer<Object, Object[]> onlineHandler;
		private ClientWebSocket webSocket;
		private Object thisLock = new Object();

		boolean isOnline() {
			return isOnline;
		}

		public Throwable getLastError() {
			return lastError;
		}

		public BiConsumer<Object, Object[]> getConnectingHandler() {
			return connectingHandler;
		}

		public void setConnectingHandler(BiConsumer<Object, Object[]> onConnecting) {
			this.connectingHandler = onConnecting;
		}

		public BiConsumer<Object, Object[]> getOfflineHandler() {
			return offlineHandler;
		}

		public void setOfflineHandler(BiConsumer<Object, Object[]> onOffline) {
			this.offlineHandler = onOffline;
		}

		public BiConsumer<Object, Object[]> getOnlineHandler() {
			return onlineHandler;
		}

		public void setOnlineHandler(BiConsumer<Object, Object[]> onOnline) {
			this.onlineHandler = onOnline;
		}

		ControlConnection(HybridConnectionListener listener) {
			this.listener = listener;
			this.address = listener.address;
			String rawPath = this.address.getPath();
			this.path = (rawPath.startsWith("/")) ? rawPath.substring(1) : rawPath;
			this.sendAsyncLock = new AsyncLock();
			this.tokenRenewer = new TokenRenewer(this.listener, this.address.toString(),
					TokenProvider.DEFAULT_TOKEN_TIMEOUT);
			this.webSocket = new ClientWebSocket(trackingContext, EXECUTOR);
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
			return CompletableFuture.runAsync(() -> {
				CompletableFuture<Void> connectTask = null;
				boolean success = false;
				
				try {
					// Block so we surface any errors to the user right away.
					connectTask = this.ensureConnectTask(timeout);
					connectTask.join();

					this.tokenRenewer.setOnTokenRenewed((token) -> this.onTokenRenewed(token));
					this.receivePumpAsync();
					success = true;
				} finally {
					if (!success) {
						this.closeOrAbortWebSocketAsync(connectTask, new CloseReason(CloseCodes.UNEXPECTED_CONDITION,
								"closing web socket connection because something went wrong trying to connect."));			
					}
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
			
			synchronized (this.thisLock) {
				this.tokenRenewer.close();

				if (this.connectAsyncTask != null) {
					 this.sendAsyncLock.acquireAsync(duration, EXECUTOR).thenCompose((lockRelease) -> {
						CloseReason reason = new CloseReason(CloseCodes.NORMAL_CLOSURE, "Normal Closure");
						
						return this.webSocket.closeAsync(reason).whenComplete((res, ex) -> {
							this.connectAsyncTask = null;
							lockRelease.release();
							if (ex != null) {
								throw new RuntimeException(ex);
							}
						});
					});
				}
				return CompletableFuture.completedFuture(null);
			}
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

		    return this.sendAsyncLock.acquireAsync(timeout, EXECUTOR).thenCompose((lockRelease) -> {
		        CompletableFuture<Void> future = this.ensureConnectTask(timeout).thenCompose((unused) -> {
		            String json = command.getResponse().toJsonString();
		            RelayLogger.logEvent("sendCommand", this, json);
		            return this.webSocket.writeAsync(json, timeout, WriteMode.TEXT);
		        });
		        
		        if (buffer != null) {
		            future = future.thenCompose((unused) -> this.webSocket.writeAsync(buffer.array()));
		        }

		        return future.handle((bytesWritten, ex) -> {
		            lockRelease.release();
		            if (ex != null) {
		            	throw new CompletionException(ex);
		            }
		            return null;
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
		private CompletableFuture<Void> ensureConnectTask(Duration timeout) {
			synchronized (this.thisLock) {
				if (this.connectAsyncTask == null) {
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
		private CompletableFuture<Void> connectAsync(Duration timeout) {
			try {
				this.listener.throwIfDisposed();
				
				CompletableFuture<Void> delayTask = CompletableFutureUtil.delayAsync(CONNECTION_DELAY_INTERVALS[this.connectDelayIndex], EXECUTOR);
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

				return delayTask.thenCompose((res) -> {
					synchronized (this.thisLock) {
						return this.webSocket.connectAsync(websocketUri, timeout, config);
					}
				}).thenRun(() -> this.onOnline());
			}
			catch (Throwable e) {
				return CompletableFutureUtil.fromException(e);
			}
		}
		
		private CompletableFuture<Void> closeOrAbortWebSocketAsync(CompletableFuture<Void> connectTask, CloseReason reason) {
			assert connectTask != null;
			assert connectTask.isDone();
			
			synchronized (this.thisLock) {
				if (connectTask == this.connectAsyncTask) {
					this.connectAsyncTask = null;
				}
				this.isOnline = false;
				
				return connectTask.thenCompose((res) -> this.webSocket.closeAsync(reason));
			}
		}
		
		@Override
		public void close() {
			this.closeAsync(null).join();
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
			return CompletableFuture.supplyAsync(() -> receivePumpCore()).handle((keepGoing, ex) -> {
				if (keepGoing) {
					receivePumpAsync();
				}
				
				if (ex != null) {
					RelayLogger.throwingException(ex, this, TraceLevel.WARNING);
				}
				this.onOffline(ex);
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
		private boolean receivePumpCore() {
			CompletableFuture<Void> connectTask = this.ensureConnectTask(null);
			boolean keepGoing = true;
			
			try {
				do {
					connectTask.join();
					String receivedMessage = this.webSocket.readTextAsync().join();

					synchronized (this.thisLock) {
						if (!this.webSocket.isOpen()) {
							this.closeOrAbortWebSocketAsync(connectTask, this.webSocket.getCloseReason());
							if (this.listener.closeCalled) {
								// This is the cloud service responding to our clean shutdown.
								keepGoing = false;
							} 
							else {
								CloseReason reason = this.webSocket.getCloseReason();
								keepGoing = this.onDisconnect(new ConnectionLostException(
										reason.getCloseCode() + ": " + reason.getReasonPhrase()
									));
							}
							break;
						}
					}
					this.listener.onCommandAsync(receivedMessage, this.webSocket);
				}
				while (keepGoing);
			} catch (Exception exception) {
				RelayLogger.handledExceptionAsWarning(exception, this.listener);
				this.closeOrAbortWebSocketAsync(connectTask, null);
				keepGoing = this.onDisconnect(exception);
			}
			return keepGoing;
		}

		private void onOnline() {
			synchronized (this.thisLock) {
				if (this.isOnline) {
					return;
				}

				this.lastError = null;
				this.isOnline = true;
				this.connectDelayIndex = -1;
			}

			RelayLogger.logEvent("connected", this.listener);
			if (this.onlineHandler != null) {
				this.onlineHandler.accept(this, null);
			}
		}

		private void onOffline(Throwable lastError) {
			synchronized (this.thisLock) {
				if (lastError != null) {
					this.lastError = lastError;
				}
				this.isOnline = false;
			}
			RelayLogger.logEvent("offline", this);
		}

		// Returns true if this control connection should attempt to reconnect after this exception.
		private boolean onDisconnect(Exception lastError) {

			synchronized (this.thisLock) {
				this.lastError = lastError;
				this.isOnline = false;

				if (this.connectDelayIndex < CONNECTION_DELAY_INTERVALS.length - 1) {
					this.connectDelayIndex++;
				}
			}

			// Inspect the close status/description to see if this is a terminal case
			// or we should attempt to reconnect.
			boolean shouldReconnect = this.shouldReconnect(lastError);

			if (shouldReconnect && this.connectingHandler != null) {
				this.connectingHandler.accept(this, null);
			}

			return shouldReconnect;
		}

		private boolean shouldReconnect(Exception exception) {
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
