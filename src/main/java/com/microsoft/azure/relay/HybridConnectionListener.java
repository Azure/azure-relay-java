package com.microsoft.azure.relay;

import java.io.UnsupportedEncodingException;
import java.net.Proxy;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.DeploymentException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.json.JSONObject;

public class HybridConnectionListener {
	private final InputQueue<ClientWebSocket> connectionInputQueue;
	private final ControlConnection controlConnection;
	private ClientWebSocket rendezvousConnection;
	private Proxy proxy;
	private boolean openCalled;
	private volatile boolean closeCalled;
	private Duration operationTimeout;
	private int maxWebSocketBufferSize;
	private Object thisLock = new Object();
	private boolean shouldAccept;

	/** 
	 * Gets a value that determines whether the connection is online.
	 * True if the connection is alive and online; False if there is no connectivity 
	 * towards the Azure Service Bus from the current network location.
	 */
	private boolean isOnline;

	/**
	 * Allows installing a custom handler which can inspect request headers, control response headers,
	 * decide whether to accept or reject a web-socket upgrade request, and control the status code/description if rejecting.
	 * The AcceptHandler should return true to accept a client request or false to reject.
	 */
	private Function<RelayedHttpListenerContext, Boolean> acceptHandler;

	/**
	 * A handler to run upon receiving Hybrid Http Requests.
	 */
	private Consumer<RelayedHttpListenerContext> requestHandler;

	/**
	 * The address on which to listen for HybridConnections. This address should be of the format "sb://contoso.servicebus.windows.net/yourhybridconnection".
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

	/**
	 * Controls whether the ClientWebSocket from .NET Core or a custom implementation is used.
	 */
	private boolean useBuiltInClientWebSocket;

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

	public Proxy getProxy() {
		return this.proxy;
	}

	public void setProxy(Proxy value) {
		this.throwIfReadOnly();
		this.proxy = value;
	}

	public boolean getUseBuiltInClientWebSocket() {
		return this.useBuiltInClientWebSocket;
	}

	public void setUseBuiltInClientWebSocket(boolean value) {
		this.useBuiltInClientWebSocket = value;
	}

	public Duration getOperationTimeout() {
		return this.operationTimeout;
	}
	
	public int getMaxWebSocketBufferSize() {
		return maxWebSocketBufferSize;
	}

	public void setMaxWebSocketBufferSize(int maxWebSocketBufferSize) {
		this.maxWebSocketBufferSize = maxWebSocketBufferSize;
	}
	
	/**
	 * Create a new HybridConnectionListener instance for accepting HybridConnections.
	 * @param address The address on which to listen for HybridConnections. This address should be of the format 
	 * "sb://contoso.servicebus.windows.net/yourhybridconnection".
	 * @param tokenProvider The TokenProvider for connecting this listener to ServiceBus.
	 */
	public HybridConnectionListener(URI address, TokenProvider tokenProvider) {
		
		if (address == null || tokenProvider == null) {
			
			// TODO: trace
//			throw RelayEventSource.Log.ThrowingException(
//					new ArgumentNullException(address == null ? nameof(address) : nameof(tokenProvider)), this);
			throw new IllegalArgumentException("address or token provider is null.");
			
		} else if (!address.getScheme().equals(RelayConstants.HYBRID_CONNECTION_SCHEME)) {
			
			// TODO: trace
//			throw RelayEventSource.Log.ThrowingException(new ArgumentException(
//					SR.InvalidUriScheme.FormatInvariant(address.Scheme, RelayConstants.HybridConnectionScheme),
//					nameof(address)), this);
			throw new IllegalArgumentException("incorrect scheme.");
		}

		this.address = address;
		this.tokenProvider = tokenProvider;
		this.operationTimeout = RelayConstants.DEFAULT_OPERATION_TIMEOUT;
//		this.proxy = WebRequest.DefaultWebProxy;
		this.trackingContext = TrackingContext.create(this.address);
		this.connectionInputQueue = new InputQueue<ClientWebSocket>();
		this.controlConnection = new ControlConnection(this);
	}

	/**
	 * Create a new HybridConnectionListener instance for accepting HybridConnections.
	 * @param connectionString The connection string to use. This connection string must include the EntityPath property.
	 * @throws URISyntaxException Thrown when the format of the connectionSring is incorrect
	 */
	public HybridConnectionListener(String connectionString) throws URISyntaxException {
		this(connectionString, null, true);
	}

	/**
	 * Creates a new instance of HybridConnectionListener from a connection string and the specified HybridConection path. 
	 * Use this overload only when the connection string does not use the RelayConnectionStringBuilder.EntityPath property.
	 * @param connectionString The connection String used. This connection string must not include the EntityPath property.
	 * @param path The path to the HybridConnection.
	 * @throws URISyntaxException Thrown when the format of the connectionSring is incorrect
	 */
	public HybridConnectionListener(String connectionString, String path) throws URISyntaxException {
		this(connectionString, path, false);
	}

	/**
	 * This private .ctor handles both of the public overloads which take connectionString
	 * @param connectionString The connection String used. This connection string must not include the EntityPath property.
	 * @param path path The path to the HybridConnection.
	 * @param pathFromConnectionString True if path is implicitly defined in the connection string
	 * @throws URISyntaxException Thrown when the format of the connectionSring is incorrect
	 */
	HybridConnectionListener(String connectionString, String path, boolean pathFromConnectionString) throws URISyntaxException {
		if (StringUtil.isNullOrWhiteSpace(connectionString)) {
			// TODO: trace
//            throw RelayEventSource.Log.ArgumentNull(nameof(connectionString), this);
			throw new IllegalArgumentException("The connectionString is null or empty.");
		}

		RelayConnectionStringBuilder builder = new RelayConnectionStringBuilder(connectionString);
		builder.validate();

		if (pathFromConnectionString) {
			if (StringUtil.isNullOrWhiteSpace(builder.getEntityPath())) {
				// TODO: trace
//                throw RelayEventSource.Log.Argument(nameof(connectionString), SR.GetString(SR.ConnectionStringMustIncludeEntityPath, nameof(HybridConnectionClient)), this);
				throw new IllegalArgumentException("ConnectionString did not have required entityPath");
			}
		} else {
			if (StringUtil.isNullOrWhiteSpace(path)) {
				// TODO: trace
//                throw RelayEventSource.Log.ArgumentNull(nameof(path), this);
				throw new IllegalArgumentException("Path parameter is required.");
			} else if (!StringUtil.isNullOrWhiteSpace(builder.getEntityPath())) {
				// TODO: trace
//                throw RelayEventSource.Log.Argument(nameof(connectionString), SR.GetString(SR.ConnectionStringMustNotIncludeEntityPath, nameof(HybridConnectionListener)), this);
				throw new IllegalArgumentException("EntityPath must not appear in connectionString");
			}

			builder.setEntityPath(path);
		}

		this.address = new URI(builder.getEndpoint() + builder.getEntityPath());
		this.tokenProvider = builder.createTokenProvider();
		this.operationTimeout = builder.getOperationTimeout();
//		this.proxy = WebRequest.DefaultWebProxy;
		this.trackingContext = TrackingContext.create(this.address);
		this.connectionInputQueue = new InputQueue<ClientWebSocket>();
		this.controlConnection = new ControlConnection(this);
	}

	/**
	 * Opens the HybridConnectionListener and registers it as a listener in ServiceBus.
	 * @return A CompletableFuture which completes when the control connection is established with the cloud service
	 */
	public CompletableFuture<Void> openAsync() {
		return this.openAsync(this.operationTimeout);
    }
	
	/**
	 * Opens the HybridConnectionListener and registers it as a listener in ServiceBus.
	 * @param timeout The timeout duration for this openAsync operation
	 * @return A CompletableFuture which completes when the control connection is established with the cloud service
	 */
	public CompletableFuture<Void> openAsync(Duration timeout) {
        TimeoutHelper.throwIfNegativeArgument(timeout);
        
        synchronized (this.thisLock) {
            this.throwIfDisposed();
            this.throwIfReadOnly();
            this.openCalled = true;
        }

        return this.controlConnection.openAsync(timeout).thenRun(() -> this.isOnline = true);
    }

	/**
	 * Disconnects all connections from the cloud service
	 * @return A CompletableFuture which completes when all connections are disconnected with the cloud service
	 */
	public CompletableFuture<Void> closeAsync() {
		return this.closeAsync(this.operationTimeout);
	}

	/**
	 * Disconnects all connections from the cloud service within the timeout
	 * @param timeout The timeout duration for this closeAsync operation
	 * @return A CompletableFuture which completes when all connections are disconnected with the cloud service
	 */
	public CompletableFuture<Void> closeAsync(Duration timeout) {
		CompletableFuture<Void> closeControlTask = new CompletableFuture<Void>();
		CompletableFuture<Void> closeRendezvousTask = new CompletableFuture<Void>();
		
        try {
        	CompletableFutureUtil.timedRunAsync(timeout, () -> {
            	List<ClientWebSocket> clients;
                synchronized (this.thisLock) {
                    if (this.closeCalled) {
                        return;
                    }

                    // TODO: trace
//                    RelayEventSource.Log.ObjectClosing(this);
                    this.closeCalled = true;

                    // If the input queue is empty this completes all pending waiters with null and prevents
                    // any new items being added to the input queue.
                    this.connectionInputQueue.shutdown();

                    // Close any unaccepted rendezvous. DequeueAsync won't block since we've called connectionInputQueue.Shutdown().
                    clients = new ArrayList<ClientWebSocket>(this.connectionInputQueue.getPendingCount());
                    ClientWebSocket webSocket;
                    
                    do {
                    	webSocket =  this.connectionInputQueue.dequeueAsync().join();
                    	
                    	if (webSocket != null) {
                    		clients.add(webSocket);
                    	}
                    } while (webSocket != null);
                    
                    this.isOnline = false;
                }
                
                clients.forEach(client -> client.closeAsync(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Client closing the socket normally")));

                // TODO: trace
//                RelayEventSource.Log.ObjectClosed(this);
        	}).join();
        }
        // TODO: trace
        catch (Exception e) // when (!Fx.IsFatal(e))
        {
//            RelayEventSource.Log.ThrowingException(e, this);
        }
        finally {
            this.connectionInputQueue.dispose();
            closeControlTask = this.controlConnection.closeAsync(null);
            if (this.rendezvousConnection != null && this.rendezvousConnection.isOpen()) {
            	closeRendezvousTask = this.rendezvousConnection.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "Listener closing rendezvous normally."));
            } else {
            	closeRendezvousTask = CompletableFuture.completedFuture(null);
            }
        }
        return CompletableFuture.allOf(closeControlTask, closeRendezvousTask);
    }

	/**
	 * Asynchronously wait for a websocket connection from the sender to be connected
	 * @return A CompletableFuture which completes when aa websocket connection from the sender is connected
	 */
	public CompletableFuture<ClientWebSocket> acceptConnectionAsync() {
        synchronized (this.thisLock) {
            if (!this.openCalled) {
            	// TODO: trace
//                throw RelayEventSource.Log.ThrowingException(new InvalidOperationException(SR.ObjectNotOpened), this);
            	throw new RuntimeException("cannot accept connection because listener is not open.");
            }
        }
        return this.connectionInputQueue.dequeueAsync();
    }

//	// <summary>
//	// Returns a String that represents the current object. Includes a TrackingId
//	// for end to end correlation.
//	// </summary>
//	public override String toString()
//    {
//        return this.cachedToString ?? (this.cachedToString = nameof(HybridConnectionListener) + "(" + this.TrackingContext + ")");
//    }
//
//	// <summary>
//	// Gets the <see cref="HybridConnectionRuntimeInformation"/> for this
//	// HybridConnection entity using the default timeout.
//	// Unless specified in the connection String the default is 1 minute.
//	// </summary>
//	public async Task<HybridConnectionRuntimeInformation> GetRuntimeInformationAsync()
//    {
//        using (var cancelSource = new CancellationTokenSource(this.OperationTimeout))
//        {
//            return await this.GetRuntimeInformationAsync(cancelSource.Token).ConfigureAwait(false);
//        }
//    }
//
//	// <summary>
//	// Gets the <see cref="HybridConnectionRuntimeInformation"/> for this
//	// HybridConnection entity using the provided CancellationToken.
//	// </summary>
//	// <param name="cancellationToken">A cancellation token to observe.</param>
//	public CompletableFuture<HybridConnectionRuntimeInformation> GetRuntimeInformationAsync(CancellationToken cancellationToken) {
//        return ManagementOperations.GetAsync<HybridConnectionRuntimeInformation>(this.address, this.tokenProvider, cancellationToken);
//    }

	CompletableFuture<Void> sendControlCommandAndStreamAsync(ListenerCommand command, ByteBuffer stream, Duration timeout) {
        return this.controlConnection.sendCommandAndStreamAsync(command, stream, timeout);
    }

	void throwIfDisposed() {
		if (this.closeCalled) {
			// TODO: trace
//			throw RelayEventSource.Log
//					.ThrowingException(new ObjectDisposedException(this.toString(), SR.EntityClosedOrAborted), this);
			throw new RuntimeException("Invalid operation. Cannot call open when it's already closed.");
		}
	}

	void throwIfReadOnly() {
		synchronized (this.thisLock) {
			if (this.openCalled) {
				throw new RuntimeException("Invalid operation. Cannot call open when it's already open.");
//				throw RelayEventSource.Log.ThrowingException(new InvalidOperationException(SR.ObjectIsReadOnly),this);
			}
		}
	}

	private void onCommandAsync(String message, ClientWebSocket controlWebSocket) {
		JSONObject jsonObj = new JSONObject(message);
		ListenerCommand listenerCommand = new ListenerCommand(jsonObj);

        if (listenerCommand.getAccept() != null) {
        	try {
				this.onAcceptCommandAsync(listenerCommand.getAccept());
			} catch (URISyntaxException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}      
        }
        else if (listenerCommand.getRequest() != null) {
        	HybridHttpConnection httpConnection = new HybridHttpConnection();
        	httpConnection.createAsync(this, listenerCommand.getRequest(), controlWebSocket);
        }
    }

    private CompletableFuture<Void> onAcceptCommandAsync(ListenerCommand.AcceptCommand acceptCommand) throws URISyntaxException {
        URI rendezvousUri = new URI(acceptCommand.getAddress());
        URI requestUri = this.generateAcceptRequestUri(rendezvousUri);

        RelayedHttpListenerContext listenerContext = new RelayedHttpListenerContext(
            this, requestUri, acceptCommand.getId(), "GET", acceptCommand.getConnectHeaders());
        listenerContext.getRequest().setRemoteAddress(acceptCommand.getRemoteEndpoint());
        
    	Function<RelayedHttpListenerContext, Boolean> acceptHandler = this.acceptHandler;
    	// TODO: setting shouldAccept as class variable or else it must be final in the block below, which is not the case
    	this.shouldAccept = acceptHandler == null;
    	
        // TODO: trace
//      RelayEventSource.Log.RelayListenerRendezvousStart(listenerContext.Listener, listenerContext.TrackingContext.TrackingId, acceptCommand.Address);
    	try {
            if (acceptHandler != null) {
                // Invoke and await the user's AcceptHandler method
            	try {
            		this.shouldAccept = acceptHandler.apply(listenerContext);
            	}
            	catch (Exception userException) {
                	// TODO: trace
//                    	when (!Fx.IsFatal(userException)) {
//                        String description = SR.GetString(SR.AcceptHandlerException, listenerContext.TrackingContext.TrackingId);
//                        RelayEventSource.Log.RelayListenerRendezvousFailed(this, listenerContext.TrackingContext.TrackingId, description + " " + userException);
//                        listenerContext.Response.StatusCode = HttpStatusCode.BadGateway;
//                        listenerContext.Response.StatusDescription = description;
            	}
            }
            // Don't block the pump waiting for the rendezvous
    		return this.completeAcceptAsync(listenerContext, rendezvousUri);
    	}
        catch (Exception exception) {
			// TODO: trace
//	        	when (!Fx.IsFatal(exception)) {
//	            RelayEventSource.Log.RelayListenerRendezvousFailed(this, listenerContext.TrackingContext.TrackingId, exception);
//	            RelayEventSource.Log.RelayListenerRendezvousStop();
        	throw new RuntimeIOException("could not connect to rendezvous.");
        }
    }

	// <summary>
	// Form the logical request Uri using the scheme://host:port from the listener
	// and the path from the acceptCommand (minus "/$hc")
	// e.g. sb://contoso.servicebus.windows.net/hybrid1?foo=bar
	// </summary>
    private URI generateAcceptRequestUri(URI rendezvousUri) throws URISyntaxException {
    	String query = HybridConnectionUtil.filterQueryString(rendezvousUri.getQuery());
    	String path = rendezvousUri.getPath();
    	path = (path.startsWith("$hc/")) ? path.substring(4) : path;
    	
    	URI address = this.address;
    	return new URI(address.getScheme(), address.getUserInfo(), address.getHost(), address.getPort(), path, query, address.getFragment());
	}

	private CompletableFuture<Void> completeAcceptAsync(RelayedHttpListenerContext listenerContext, URI rendezvousUri) {
		try {
            if (this.shouldAccept) {
            	if (this.rendezvousConnection == null || !this.rendezvousConnection.isOpen()) this.rendezvousConnection = new ClientWebSocket();
                
                synchronized (this.thisLock) {
                    if (this.closeCalled) {
                    	// TODO: trace
//                        RelayEventSource.Log.RelayListenerRendezvousFailed(this, listenerContext.getTrackingContext().getTrackingId(), SR.ObjectClosedOrAborted);
                        return null;
                    }
                    return CompletableFuture.completedFuture(null).thenCompose((empty) -> this.rendezvousConnection.connectAsync(rendezvousUri))
                    		.thenRun(() -> this.connectionInputQueue.enqueueAndDispatch(this.rendezvousConnection, null, false));
                }
            }
            else {
            	// TODO: trace
//                RelayEventSource.Log.RelayListenerRendezvousRejected(
//                    listenerContext.TrackingContext, listenerContext.Response.StatusCode, listenerContext.Response.StatusDescription);
            	return CompletableFuture.completedFuture(null).thenCompose((empty) -> {
					try {
						return listenerContext.rejectAsync(rendezvousUri);
					} 
					catch (UnsupportedEncodingException | URISyntaxException | DeploymentException | CompletionException e) {
						// TODO Auto-generated catch block
						throw new RuntimeException(e.getMessage());
					}
				});
            }
        }
        catch (Exception exception) {
        // TODO: trace
//		when (!Fx.IsFatal(exception)) {
//            RelayEventSource.Log.RelayListenerRendezvousFailed(this, listenerContext.TrackingContext.TrackingId, exception);
        }
        finally {
            // TODO: trace
//        	RelayEventSource.Log.RelayListenerRendezvousStop();
        }
		return CompletableFuture.completedFuture(null);
    }

	void onConnectionStatus(BiConsumer<Object, Object[]> handler, Object sender, Object[] args) {
//	void OnConnectionStatus(EventHandler handler, object sender, EventArgs args) {
		// Propagate inner properties in case they've mutated.
//		var innerStatus=(IConnectionStatus)sender;
//		this.IsOnline=innerStatus.IsOnline;
//		this.LastError=innerStatus.LastError;
		if (handler != null)
			handler.accept(sender,args);
	}

	/**
	 * Connects, maintains, and transparently reconnects this listener's control connection with the cloud service.
	 */
	final class ControlConnection {
		// Retries after 0, 1, 2, 5, 10, 30 seconds
		final Duration[] CONNECTION_DELAY_INTERVALS = { Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2),
				Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30) };
		private final HybridConnectionListener listener;
		private final URI address;
		private String path;
		private final TokenRenewer tokenRenewer;
		private final AsyncLock sendAsyncLock;
		private CompletableFuture<Void> connectAsyncTask;
		private int connectDelayIndex;
		private boolean isOnline;
		private Exception lastError;
		private BiConsumer<Object, Object[]> connectingHandler;
		private BiConsumer<Object, Object[]> offlineHandler;
		private BiConsumer<Object, Object[]> onlineHandler;
		private CompletableFuture<Void> receiveTask;
		private ClientWebSocket webSocket;
		private Object thisLock = new Object();
		
		boolean isOnline() {
			return isOnline;
		}

		public Exception getLastError() {
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
			this.tokenRenewer = new TokenRenewer(this.listener, this.address.toString(), TokenProvider.DEFAULT_TOKEN_TIMEOUT);
			this.webSocket = new ClientWebSocket();
		}

		/**
		 * Establish websocket connection between the control websocket and the cloud service, then start receiving command messages
		 * @param timeout The timeout to connect to cloud service within
		 * @return Returns a completableFuture which completes when websocket connection is 
		 * 		established between the control websocket and the cloud service
		 */
		public CompletableFuture<Void> openAsync(Duration timeout) {
            // Esstablish a WebSocket connection right now so we can detect any fatal errors
            return CompletableFuture.runAsync(() -> {
            	boolean succeeded = false;
            	CompletableFuture<Void> connectTask = null;
	            try {
	                // Block so we surface any errors to the user right away.
	            	connectTask = this.ensureConnectTask(timeout);
	            	connectTask.join();
	            	
	                this.tokenRenewer.setOnTokenRenewed((token) -> this.onTokenRenewed(token));
	                this.receiveTask = CompletableFuture.runAsync(() -> this.receivePumpAsync());
	                succeeded = true;
	            } catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	            finally {
	                if (!succeeded) {
	                    this.closeOrAbortWebSocketAsync(connectTask, new CloseReason(CloseCodes.UNEXPECTED_CONDITION, "closing web socket connection because something went wrong trying to connect."));
	                }
	            }
            });
        }

		/**
		 * Ensures connection of the control websocket, then disconnects it from the cloud service
		 * @param timeout The timeout to disconnect to cloud service within
		 * @return Returns a completableFuture which completes when websocket connection is 
		 * 		established between the control websocket and the cloud service
		 */
		private CompletableFuture<Void> closeAsync(Duration duration) {
            CompletableFuture<Void> connectTask;
            synchronized (this.thisLock) {
                connectTask = this.connectAsyncTask;
                this.connectAsyncTask = null;
            }

            this.tokenRenewer.close();

            // Start a clean close by first calling CloseOutputAsync. The finish (CloseAsync) happens when
            // the receive pump task finishes working.
            if (connectTask != null) {
                return this.sendAsyncLock.lockAsync(duration).thenCompose((lockRelease) -> {
                	CloseReason reason = new CloseReason(CloseCodes.NORMAL_CLOSURE, "Normal Closure");
                	lockRelease.release();
                	return this.webSocket.closeAsync(reason);
                });
            }
            return CompletableFuture.completedFuture(null);
        }

		/**
		 * Sends the command through the control websocket, along with the message body if it exists
		 * @param command The Listener command to be sent
		 * @param stream The messge body to be sent, null if it doesn't exist
		 * @param timeout The timeout to send within
		 * @return Returns a completableFuture which completes when the command and stream are finished sending
		 */
		private CompletableFuture<Void> sendCommandAndStreamAsync(ListenerCommand command, ByteBuffer stream, Duration timeout) {

			return this.sendAsyncLock.lockAsync(timeout).thenComposeAsync((lockRelease) -> {
				try {
					this.ensureConnectTask(timeout).join();
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}

				String json = command.getResponse().toJsonString();
				
				CompletableFuture<Void> future = new CompletableFuture<Void>();
				// sends the command then send the actual message
				try {
					future = this.webSocket.sendCommandAsync(json, timeout).thenComposeAsync((sendCommandResult) -> {
						return (stream != null) ? webSocket.sendAsync(stream.array()) : CompletableFuture.completedFuture(null);
					}).thenRun(() -> {
						lockRelease.release();
					});
				} catch (CompletionException e) {
					e.printStackTrace(); // should not be exception here because timeout is null
				}
				return future;
			});
		}

		/**
		 * Establish websocket connection between the control websocket and the cloud service if not already established.
		 * @param timeout The timeout to connect to cloud service within
		 * @return Returns a completableFuture which completes when websocket connection is 
		 * 		established between the control websocket and the cloud service
		 * @throws InterruptedException Throws when interrupted during the connection delay
		 */
		private CompletableFuture<Void> ensureConnectTask(Duration timeout) throws InterruptedException {
			synchronized (this.thisLock) {
				if (this.connectAsyncTask == null) {
					this.connectAsyncTask = this.connectAsync(timeout);
				}
				return this.connectAsyncTask;
			}
		}

		/**
		 * Establish websocket connection between the control websocket and the cloud service.
		 * @param timeout The timeout to connect to cloud service within
		 * @return Returns a completableFuture which completes when websocket connection is 
		 * 		established between the control websocket and the cloud service
		 * @throws InterruptedException Throws when interrupted during the connection delay
		 */
		private CompletableFuture<Void> connectAsync(Duration timeout) throws InterruptedException {
            this.listener.throwIfDisposed();
            
            Map<String, List<String>> headers = new HashMap<String, List<String>>();
            CompletableFuture<SecurityToken> token = this.tokenRenewer.getTokenAsync();
            
            Duration connectDelay = CONNECTION_DELAY_INTERVALS[this.connectDelayIndex];
            if (connectDelay != Duration.ZERO) {
				this.thisLock.wait(connectDelay.toMillis());
            }

            // TODO: set options
//                RelayEventSource.Log.ObjectConnecting(this.listener);
//                webSocket.Options.SetBuffer(this.bufferSize, this.bufferSize);
//                webSocket.Options.Proxy = this.listener.Proxy;
//                webSocket.Options.KeepAliveInterval = HybridConnectionConstants.KeepAliveInterval;
//                webSocket.Options.SetRequestHeader(HybridConnectionConstants.Headers.RelayUserAgent, HybridConnectionConstants.ClientAgent);
            
            // Set the authentication in request header
            try {
				headers.put(RelayConstants.SERVICEBUS_AUTHORIZATION_HEADER_NAME, Arrays.asList(token.get().getToken()));
			} catch (InterruptedException | ExecutionException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
            HybridConnectionEndpointConfigurator.setHeaders(headers);

            // When we reconnect we need to remove the "_GXX" suffix otherwise trackingId gets longer after each reconnect
            String trackingId = TrackingContext.removeSuffix(this.listener.trackingContext.getTrackingId());

            // Build the websocket uri, e.g. "wss://contoso.servicebus.windows.net:443/$hc/endpoint1?sb-hc-action=listen&sb-hc-id=E2E_TRACKING_ID"
			URI websocketUri;
			try {
				websocketUri = HybridConnectionUtil.BuildUri(
					    this.address.getHost(),
					    this.address.getPort(),
					    this.address.getPath(),
					    this.address.getQuery(),
					    HybridConnectionConstants.Actions.LISTEN,
					    trackingId
				);
			} catch (URISyntaxException e) {
				throw new IllegalArgumentException("uri is invalid.");
			}
			
			return this.webSocket.connectAsync(websocketUri, timeout).thenRun(() -> this.onOnline());
            
            // TODO: trace
//                RelayEventSource.Log.ObjectConnected(this.listener);
        }

		private CompletableFuture<Void> closeOrAbortWebSocketAsync(CompletableFuture<Void> connectTask, CloseReason reason) {
			
			// TODO: fx
//            Fx.Assert(connectTask != null, "CloseWebSocketAsync was called with null connectTask");
//            Fx.Assert(connectTask.IsCompleted || !abort, "CloseOrAbortWebSocketAsync(abort=true) should only be called with a completed connectTask");
            
			synchronized (this.thisLock) {
                if (connectTask == this.connectAsyncTask) {
                    this.connectAsyncTask = null;
                }
            }

            try {
                this.isOnline = false;
                this.receiveTask.cancel(true);
                return connectTask.thenRun(() -> this.webSocket.closeAsync(reason));
            }
            catch (Exception e)
//            when (!Fx.IsFatal(e))
            {
            	// TODO: trace
//                RelayEventSource.Log.HandledExceptionAsWarning(this.listener, e);
                return this.webSocket.closeAsync(null);
            }
        }

		/**
		 * Ensure we have a connected control webSocket, listens for command messages, and handles those messages.
		 * @return A boolean indicating whether or not the receive pump should keep running.
		 * @throws InterruptedException
		 */
		private CompletableFuture<Void> receivePumpAsync() {
            Exception exception = null;
            try {
                boolean keepGoing;
                do {
                    keepGoing = this.receivePumpCoreAsync().get();
                }
                while (keepGoing);
//                while (keepGoing && !this.shutdownCancellationSource.IsCancellationRequested);
            }
            catch (Exception e) 
//            TODO: when (!Fx.IsFatal(e)) 
            {
            	// TODO: trace
//                RelayEventSource.Log.HandledExceptionAsWarning(this.listener, e);
                exception = e;
            }
            finally {
                this.onOffline(exception);
            }
            return CompletableFuture.completedFuture(null);
        }

		/**
		 * Ensure we have a connected control webSocket, listens for command messages, and handles those messages.
		 * @return A CompletableFuture boolean which completes when the control websocket is disconnected and 
		 * 		indicates whether or not the receive pump should keep running.
		 * @throws InterruptedException
		 */
		private CompletableFuture<Boolean> receivePumpCoreAsync() throws InterruptedException {
            CompletableFuture<Void> connectTask = this.ensureConnectTask(null);
            
            return CompletableFuture.supplyAsync(() -> {
            	boolean keepGoing = true;
            	try {
	                do {
	                	connectTask.get();
	                	String receivedMessage = this.webSocket.receiveControlMessageAsync().get();
	                    
	                    if (!this.webSocket.isOpen()) {
	                        this.closeOrAbortWebSocketAsync(connectTask, this.webSocket.getCloseReason());
	                        if (this.listener.closeCalled) {
	                            // This is the cloud service responding to our clean shutdown.
	                            keepGoing = false;
	                        }
	                        else {
	                            keepGoing = this.onDisconnect(new ConnectionLostException(
	                            		this.webSocket.getCloseReason().getCloseCode() + ": " + this.webSocket.getCloseReason().getReasonPhrase()));
	                        }
	                        break;
	                    }

	                    this.listener.onCommandAsync(receivedMessage, this.webSocket);
	                }
	                while (keepGoing);
	            } 
	            catch (Exception exception) 
	//            TODO: when (!Fx.IsFatal(exception))
	            {
	            	// TODO: trace
	//                RelayEventSource.Log.HandledExceptionAsWarning(this.listener, exception);
	                this.closeOrAbortWebSocketAsync(connectTask, null);
	                keepGoing = this.onDisconnect(exception);
	            }
            	return keepGoing;
            });
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

			// TODO: trace
//			RelayEventSource.Log.Info(this.listener,"Online");
			if (this.onlineHandler != null) {
				this.onlineHandler.accept(this, null);
			}
		}

		private void onOffline(Exception lastError) {
			synchronized (this.thisLock) {
				if (lastError != null) {
					this.lastError = lastError;
				}
				this.isOnline = false;
			}

			// TODO: trace
			// Stop attempting to connect
//			RelayEventSource.Log.Info(this.listener,$"Offline. {this.listener.TrackingContext}");this.Offline?.Invoke(this,EventArgs.Empty);
		}

		// Returns true if this control connection should attempt to reconnect after
		// this exception.
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
			
			if(shouldReconnect && this.connectingHandler != null) {
				this.connectingHandler.accept(this, null);
			}

			return shouldReconnect;
		}

		private boolean shouldReconnect(Exception exception) {
			return (!(exception instanceof EndpointNotFoundException));
		}

		private void onTokenRenewed(SecurityToken token) {
            try {
            	ListenerCommand listenerCommand = new ListenerCommand(null);
            	listenerCommand.setRenewToken(listenerCommand.new RenewTokenCommand(null));
                listenerCommand.getRenewToken().setToken(token.toString());

                this.sendCommandAndStreamAsync(listenerCommand, null, null);
            }
            catch (Exception exception) {
            // TODO: when (!Fx.IsFatal(exception))
//                RelayEventSource.Log.HandledExceptionAsWarning(this.listener, exception);
            }
        }
	}
}
