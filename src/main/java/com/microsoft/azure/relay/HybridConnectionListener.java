package com.microsoft.azure.relay;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.json.JSONObject;

public class HybridConnectionListener {
	private final InputQueue<ClientWebSocket> connectionInputQueue;
	private final ControlConnection controlConnection;
	private Proxy proxy;
	private String cachedToString;
	private boolean openCalled;
	private volatile boolean closeCalled;
	private Duration operationTimeout;
	private Object thisLock = new Object();
	private boolean shouldAccept;

	// <summary>Gets a value that determines whether the connection is
	// online.</summary>
	// <value>true if the connection is alive and online; false if there
	// is no connectivity towards the Azure Service Bus from the current network
	// location.</value>
	private boolean isOnline;

	// <summary>Retrieves the last error encountered when trying to reestablish the
	// connection from the offline state.</summary>
	// <value>Returns a <see cref="System.Exception" /> that contains the last
	// error.</value>
	private Exception lastError;

	// <summary>
	// Allows installing a custom handler which can inspect request headers,
	// control response headers,
	// decide whether to accept or reject a web-socket upgrade request, and control
	// the status code/description if rejecting.
	// The AcceptHandler should return true to accept a client request or false to
	// reject.
	// </summary>
	private Function<RelayedHttpListenerContext, Boolean> acceptHandler;

	// <summary>
	// Installs a handler for Hybrid Http Requests.
	// </summary>
	private Consumer<RelayedHttpListenerContext> requestHandler;

	// <summary>
	// Gets the address on which to listen for HybridConnections. This address
	// should be of the format
	// "sb://contoso.servicebus.windows.net/yourhybridconnection".
	// </summary>
	private URI address;

	// <summary>
	// Gets the TrackingContext for this listener.
	// </summary>
	private TrackingContext trackingContext;

	// <summary>
	// Gets the TokenProvider for authenticating this HybridConnection listener.
	// </summary>
	private TokenProvider tokenProvider;

	// <summary>
	// Controls whether the ClientWebSocket from .NET Core or a custom
	// implementation is used.
	// </summary>
	private boolean useBuiltInClientWebSocket;

	// <summary>
	// Gets or sets the connection buffer size. Default value is 64K.
	// </summary>
	private int connectionBufferSize;
	
	// <summary>
	// Raised when the Listener is attempting to reconnect with ServiceBus after a connection loss.
	// </summary>
	private BiConsumer<Object, Object[]> onConnecting;

	// <summary>
	// Raised when the Listener has successfully connected with ServiceBus
	// </summary>
	private BiConsumer<Object, Object[]> onOnline;

	// <summary>
	// Raised when the Listener will no longer be attempting to (re)connect with ServiceBus.
	// </summary>
	private BiConsumer<Object, Object[]> onOffline;

	public boolean getIsOnline() {
		return this.isOnline;
	}

	public Exception getLastError() {
		return this.lastError;
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
	
	public BiConsumer<Object, Object[]> getConnectingHandler() {
		return onConnecting;
	}

	public void setConnectingHandler(BiConsumer<Object, Object[]> onConnecting) {
		this.onConnecting = onConnecting;
	}

	public BiConsumer<Object, Object[]> getOnlineHandler() {
		return onOnline;
	}

	public void setOnlineHandler(BiConsumer<Object, Object[]> onOnline) {
		this.onOnline = onOnline;
	}

	public BiConsumer<Object, Object[]> getOfflineHandler() {
		return onOffline;
	}

	public void setOfflineHandler(BiConsumer<Object, Object[]> onOffline) {
		this.onOffline = onOffline;
	}
	
	// <summary>
	// Create a new HybridConnectionListener instance for accepting
	// HybridConnections.
	// </summary>
	// <param name="address">The address on which to listen for HybridConnections.
	// This address should
	// be of the format
	// "sb://contoso.servicebus.windows.net/yourhybridconnection".</param>
	// <param name="tokenProvider">The TokenProvider for connecting this listener
	// to ServiceBus.</param>
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
		this.useBuiltInClientWebSocket = HybridConnectionConstants.DEFAULT_USE_BUILTIN_CLIENT_WEBSOCKET;
	}

	// <summary>Creates a new instance of <see cref="HybridConnectionListener" />
	// using the specified connection String.</summary>
	// <param name="connectionString">The connection String to use. This connection
	// String must include the EntityPath property.</param>
	// <returns>The newly created <see cref="HybridConnectionListener" />
	// instance.</returns>
	// <exception cref="System.ArgumentException">Thrown when the format of the
	// <paramref name="connectionString" /> parameter is incorrect.</exception>
	public HybridConnectionListener(String connectionString) throws URISyntaxException {
		this(connectionString, null, true);
	}

	// <summary>Creates a new instance of <see cref="HybridConnectionListener" />
	// from a connection String and
	// the specified HybridConection path. Use this overload only when the
	// connection String does not use the
	// <see cref="RelayConnectionStringBuilder.EntityPath" /> property.</summary>
	// <param name="connectionString">The connection String used. This connection
	// String must not include the EntityPath property.</param>
	// <param name="path">The path to the HybridConnection.</param>
	// <returns>The created <see cref="HybridConnectionListener" />.</returns>
	// <exception cref="System.ArgumentException">Thrown when the format of the
	// <paramref name="connectionString" /> parameter is incorrect.</exception>
	public HybridConnectionListener(String connectionString, String path) throws URISyntaxException {
		this(connectionString, path, false);
	}

	// This private .ctor handles both of the public overloads which take
	// connectionString
	HybridConnectionListener(String connectionString, String path, boolean pathFromConnectionString) throws URISyntaxException {
		if (StringUtil.isNullOrWhiteSpace(connectionString)) {
			// TODO: trace
//            throw RelayEventSource.Log.ArgumentNull(nameof(connectionString), this);
		}

		RelayConnectionStringBuilder builder = new RelayConnectionStringBuilder(connectionString);
		builder.validate();

		if (pathFromConnectionString) {
			if (StringUtil.isNullOrWhiteSpace(builder.getEntityPath())) {
				// connectionString did not have required EntityPath
				// TODO: trace
//                throw RelayEventSource.Log.Argument(nameof(connectionString), SR.GetString(SR.ConnectionStringMustIncludeEntityPath, nameof(HybridConnectionClient)), this);
			}
		} else {
			if (StringUtil.isNullOrWhiteSpace(path)) {
				// path parameter is required
				// TODO: trace
//                throw RelayEventSource.Log.ArgumentNull(nameof(path), this);
			} else if (!StringUtil.isNullOrWhiteSpace(builder.getEntityPath())) {
				// EntityPath must not appear in connectionString
				// TODO: trace
//                throw RelayEventSource.Log.Argument(nameof(connectionString), SR.GetString(SR.ConnectionStringMustNotIncludeEntityPath, nameof(HybridConnectionListener)), this);
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
		this.useBuiltInClientWebSocket = HybridConnectionConstants.DEFAULT_USE_BUILTIN_CLIENT_WEBSOCKET;
	}

	// <summary>
	// Opens the <see cref="HybridConnectionListener"/> and registers it as a
	// listener in ServiceBus.
	// </summary>
	public CompletableFuture<Void> openAsync() {
		return this.openAsync(this.operationTimeout);
    }
	
	// <summary>
	// Opens the <see cref="HybridConnectionListener"/> and registers it as a
	// listener in ServiceBus.
	// </summary>
	// <param name="timeout">A timeout to observe.</param>
	public CompletableFuture<Void> openAsync(Duration timeout) {
        TimeoutHelper.throwIfNegativeArgument(timeout);
        
        synchronized (this.thisLock) {
            this.throwIfDisposed();
            this.throwIfReadOnly();
            this.openCalled = true;
        }

        // Hookup IConnectionStatus events
        this.controlConnection.setConnectingHandler((s, e) -> this.onConnectionStatus(this.onConnecting, s, e));
        this.controlConnection.setOnlineHandler((s, e) -> this.onConnectionStatus(this.onOnline, s, e));
        this.controlConnection.setOfflineHandler((s, e) -> this.onConnectionStatus(this.onOffline, s, e));

        return this.controlConnection.openAsync(timeout);
    }

	// <summary>
	// Closes the <see cref="HybridConnectionListener"/> using the default timeout.
	// Unless specified in the connection String the default is 1 minute.
	// </summary>
	public CompletableFuture<Void> closeAsync() {
		return this.closeAsync(this.operationTimeout);
	}

	// <summary>
	// Closes the <see cref="HybridConnectionListener"/> using the provided
	// CancellationToken.
	// </summary>
	// <param name="cancellationToken">A cancellation token to observe.</param>
	// TODO: cancellationtoken param
	public CompletableFuture<Void> closeAsync(Duration timeout) {
//        try {
//        	List<Session> clients;
//            synchronized (this.thisLock) {
//                if (this.closeCalled) {
//                    return null;
//                }
//
//                // TODO: trace
////                RelayEventSource.Log.ObjectClosing(this);
//                this.closeCalled = true;
//
//                // If the input queue is empty this completes all pending waiters with null and prevents
//                // any new items being added to the input queue.
//                this.connectionInputQueue.shutdown();
//
//                // Close any unaccepted rendezvous.  DequeueAsync won't block since we've called connectionInputQueue.Shutdown().
//                clients = new ArrayList<Session>(this.connectionInputQueue.getPendingCount());
//                Session session;
//                
//                while ((session = TimedCompletableFuture.timedSupplyAsync(timeout, () ->
//                	this.connectionInputQueue.dequeueAsync().get()).get()) != null) {
//                    
//                	clients.add(session);
//                }
//            }
//
//            CompletableFuture<Void> closeControl = TimedCompletableFuture.timedRunAsync(timeout, () -> this.controlConnection.closeAsync(timeout));
//
//            clients.forEach(client -> client.close());
//
//            // TODO: trace
////            RelayEventSource.Log.ObjectClosed(this);
//        }
//        // TODO: fx
//        catch (Exception e) // when (!Fx.IsFatal(e))
//        {
////            RelayEventSource.Log.ThrowingException(e, this);
//            System.out.println(e.getMessage());
//        }
//        finally {
//            this.connectionInputQueue.dispose(); 
//        }
        return CompletableFuture.completedFuture(null);
    }

	// <summary>
	// Accepts a new HybridConnection which was initiated by a remote client and
	// returns the Stream.
	// </summary>
	public CompletableFuture<ClientWebSocket> acceptConnectionAsync() {
        synchronized (this.thisLock) {
            if (!this.openCalled) {
            	// TODO: trace
//                throw RelayEventSource.Log.ThrowingException(new InvalidOperationException(SR.ObjectNotOpened), this);
            	throw new RuntimeException("cannot accept connection because socket is not open.");
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
		}
	}

	void throwIfReadOnly() {
		synchronized (this.thisLock) {
			if(this.openCalled) {
				throw new RuntimeException("invalid operation");
//				throw RelayEventSource.Log.ThrowingException(new InvalidOperationException(SR.ObjectIsReadOnly),this);
			}
		}
	}

	private void onCommandAsync(String message, ClientWebSocket controlWebSocket) {
		System.out.println("received message");
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
		
		
//        try {
//            var listenerCommand = ListenerCommand.ReadObject(commandBuffer);
//            if (listenerCommand.Accept != null) {
//                this.onAcceptCommandAsync(listenerCommand.Accept).ConfigureAwait(false);
//            }
//            else if (listenerCommand.Request != null) {
//                HybridHttpConnection.createAsync(this, listenerCommand.Request, webSocket).ConfigureAwait(false);
//            }
//            else {
//                String json = Encoding.UTF8.GetString(
//                    commandBuffer.Array,
//                    commandBuffer.Offset,
//                    Math.Min(commandBuffer.Count, HybridConnectionConstants.MaxUnrecognizedJson));
//                RelayEventSource.Log.Warning(this, $"Received an unknown command: {json}.");
//            }
//        }
//        catch (Exception exception) when (!Fx.IsFatal(exception))
//        {
//            RelayEventSource.Log.HandledExceptionAsWarning(this, exception);
//        }
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
        return CompletableFuture.runAsync(() -> {
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
        		CompletableFuture.runAsync(() -> {
        			this.completeAcceptAsync(listenerContext, rendezvousUri);
        		});
        	}
            catch (Exception exception) {
				// TODO: trace
//	        	when (!Fx.IsFatal(exception)) {
//	            RelayEventSource.Log.RelayListenerRendezvousFailed(this, listenerContext.TrackingContext.TrackingId, exception);
//	            RelayEventSource.Log.RelayListenerRendezvousStop();
            }
        });
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
            	ClientWebSocket webSocket = new ClientWebSocket("HybridConnection rendezvous");
                
                synchronized (this.thisLock) {
                    if (this.closeCalled) {
                    	// TODO: trace
//                        RelayEventSource.Log.RelayListenerRendezvousFailed(this, listenerContext.getTrackingContext().getTrackingId(), SR.ObjectClosedOrAborted);
                        return null;
                    }
                    return CompletableFuture.runAsync(() -> {
                    	webSocket.connectAsync(rendezvousUri);
                    	this.connectionInputQueue.enqueueAndDispatch(webSocket, null, false);
                    });
                }
            }
            else {
            	// TODO: trace
//                RelayEventSource.Log.RelayListenerRendezvousRejected(
//                    listenerContext.TrackingContext, listenerContext.Response.StatusCode, listenerContext.Response.StatusDescription);
            	return CompletableFuture.runAsync(() -> {
					try {
						listenerContext.rejectAsync(rendezvousUri);
					} 
					catch (UnsupportedEncodingException | URISyntaxException | DeploymentException e) {
						// TODO Auto-generated catch block
							e.printStackTrace();
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

	// Connects, maintains, and transparently reconnects this listener's control
	// connection with the cloud service.
	public final class ControlConnection {
		// Retries after 0, 1, 2, 5, 10, 30 seconds
		final Duration[] CONNECTION_DELAY_INTERVALS = { Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2),
				Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30) };

		// TODO: cancellationtoken
//		private final CancellationTokenSource shutdownCancellationSource;
//		private CancellationToken closeCancellationToken;
		private final HybridConnectionListener listener;
		private final URI address;
		private final String path;
		private final TokenRenewer tokenRenewer;
		private final AsyncLock sendAsyncLock;
		private CompletableFuture<ClientWebSocket> connectAsyncTask;
		private CompletableFuture<Void> receivePumpTask;
		private int connectDelayIndex;
		private boolean isOnline;
		private Exception lastError;
		private BiConsumer<Object, Object[]> connectingHandler;
		private BiConsumer<Object, Object[]> offlineHandler;
		private BiConsumer<Object, Object[]> onlineHandler;
		private ClientWebSocket webSocket;
		private Object thisLock = new Object();
		
		public boolean isOnline() {
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

		public ControlConnection(HybridConnectionListener listener) {
			this.listener = listener;
			this.address = listener.address;
			String rawPath = this.address.getPath();
			this.path = (rawPath.startsWith("/")) ? rawPath.substring(1) : rawPath;
//			this.shutdownCancellationSource = new CancellationTokenSource();
			this.sendAsyncLock = new AsyncLock();
			this.tokenRenewer = new TokenRenewer(this.listener, this.address.toString(), TokenProvider.DEFAULT_TOKEN_TIMEOUT);
			this.webSocket = new ClientWebSocket("HybridconnectionListener control");
		}

		// TODO: cancellationtoken
		public CompletableFuture<Void> openAsync(Duration timeout) {
            // Esstablish a WebSocket connection right now so we can detect any fatal errors
            return CompletableFuture.runAsync(() -> {
            	boolean succeeded = false;
	            try {
	                // Block so we surface any errors to the user right away.
	            	ensureConnectTask(timeout).get();
	//                this.tokenRenewer.setOnTokenRenewed((token) -> this.onTokenRenewed(token));
	//                this.receivePumpTask = this.ReceivePumpAsync();
	                succeeded = true;
	            }
	            catch (InterruptedException | ExecutionException e) {
	            	System.out.println(e.getMessage());
	            }
	            finally {
	                if (!succeeded) {
	//                    await this.CloseOrAbortWebSocketAsync(connectTask, abort: true).ConfigureAwait(false);
	                }
	            }
            });
        }

		// TODO: cancellationtoken
		public CompletableFuture<Void> closeAsync(Duration duration) {
//            CompletableFuture<ClientWebSocket> connectTask;
//            synchronized (this.thisLock) {
////                this.closeCancellationToken = cancellationToken;
//                connectTask = this.connectAsyncTask;
//                this.connectAsyncTask = null;
//            }
//
//            this.tokenRenewer.close();
////            this.shutdownCancellationSource.Cancel();
//
//            // Start a clean close by first calling CloseOutputAsync. The finish (CloseAsync) happens when
//            // the receive pump task finishes working.
//            if (connectTask != null) {
//                ClientWebSocket webSocket = connectTask.get();
//                this.sendAsyncLock.lockAsync(duration).thenRunAsync(() -> {
//                	webSocket.setOnDisconnect(this.onDisconnect);
//                	CloseReason closeReason = reason;
//                	System.out.println("Socket Closed: " + reason);
//                });
//            }
//
//            if (this.receivePumpTask != null)
//            {
//                await this.receivePumpTask.ConfigureAwait(false);
//            }
            return null;
        }

		// TODO: cancellationtoken param
		CompletableFuture<Void> sendCommandAndStreamAsync(ListenerCommand command, ByteBuffer stream, Duration timeout) {
			
			return this.sendAsyncLock.lockAsync(timeout).thenApplyAsync((lock) -> {
				CompletableFuture<ClientWebSocket> connectTask = null;
				try {
					connectTask = this.ensureConnectTask(timeout);
				} catch (InterruptedException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				CompletableFuture<Void> sendCommand;
				CompletableFuture<Void> sendStream;
				String json = JSONObject.valueToString(command);
				
				ClientWebSocket webSocket = null;
				try {
					webSocket = connectTask.get();
				} catch (InterruptedException | ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				sendCommand = webSocket.sendAsync(json);
				sendStream = (stream != null) ? webSocket.sendAsync(stream.array(), timeout) : CompletableFuture.completedFuture(null);

				return CompletableFuture.allOf(sendCommand, sendStream);
			}).thenAccept((completedBoth) -> {});
		}

		// <summary>
		// Gets or potentially creates a viable Future<ServerWebSocket>. If the existing
		// one is faulted or canceled a new one is created.
		// </summary>
		// TODO: cancellationtoken param
		private CompletableFuture<ClientWebSocket> ensureConnectTask(Duration timeout) throws InterruptedException {
			
			synchronized (this.thisLock) {
				// TODO: isFaulted, isCanceled
//				if (this.connectAsyncTask == null || this.connectAsyncTask.IsFaulted||this.connectAsyncTask.IsCanceled) {
				if (this.connectAsyncTask == null) {
					this.connectAsyncTask = this.connectAsync(timeout);
				}
				return this.connectAsyncTask;
			}
		}

		// TODO: cancellationtoken param
		private CompletableFuture<ClientWebSocket> connectAsync(Duration timeout) throws InterruptedException {
            this.listener.throwIfDisposed();
            
            Map<String, List<String>> headers = new HashMap<String, List<String>>();
            CompletableFuture<SecurityToken> token = this.tokenRenewer.getTokenAsync();
            
//            ServerWebSocket webSocket = ClientWebSocketFactory.Create(this.listener.UseBuiltInClientWebSocket);
//            try {
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
					// TODO Auto-generated catch block
					throw new IllegalArgumentException("uri is invalid.");
				}
				
				return CompletableFuture.supplyAsync(() -> {
					this.webSocket.setOnMessage((msg) -> this.onMessage(msg));
					try {
						this.webSocket.connectAsync(websocketUri).get();
					} catch (InterruptedException | ExecutionException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					this.onOnline();
					return this.webSocket;
				});
                
//                // TODO: trace
////                RelayEventSource.Log.ObjectConnected(this.listener);
//                Session mysession = null;
//				try {
//					mysession = session.get();
//				} catch (ExecutionException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//                if (mysession.isOpen()) {
//                	System.out.println("open.");
//                }
//                return webSocket;
//            }
//            catch (WebSocketException wsException) {
//                throw RelayEventSource.Log.ThrowingException(
//                    WebSocketExceptionHelper.ConvertToRelayContract(wsException, this.listener.TrackingContext, webSocket.Response), this.listener);
//            }
        }

		// TODO: cancellationtoken param
//		private CompletableFuture<Void> closeOrAbortWebSocketAsync(
//            CompletableFuture<WebSocket> connectTask,
//            boolean abort,
//            WebSocketCloseStatus closeStatus,
//            String statusDescription) {
			
//			if (closeStatus == null) closeStatus = WebSocketCloseStatus.EMPTY;
//			
//			// TODO: fx
////            Fx.Assert(connectTask != null, "CloseWebSocketAsync was called with null connectTask");
////            Fx.Assert(connectTask.IsCompleted || !abort, "CloseOrAbortWebSocketAsync(abort=true) should only be called with a completed connectTask");
//            
//			synchronized (this.thisLock) {
//                if (object.ReferenceEquals(connectTask, this.connectAsyncTask)) {
//                    this.connectAsyncTask = null;
//                }
//            }
//
//            WebSocket webSocket = null;
//            try {
//                webSocket = await connectTask.ConfigureAwait(false);
//                if (abort) {
//                    webSocket.Abort();
//                }
//                else {
//                    await webSocket.CloseOutputAsync(closeStatus, statusDescription, cancellationToken).ConfigureAwait(false);
//                    await webSocket.CloseAsync(closeStatus, statusDescription, cancellationToken).ConfigureAwait(false);
//                }
//            }
//            catch (Exception e) when (!Fx.IsFatal(e))
//            {
//                RelayEventSource.Log.HandledExceptionAsWarning(this.listener, e);
//                webSocket?.Abort();
//            }
//        }

//		private CompletableFuture<Void> receivePumpAsync() {
//            Exception exception = null;
//            try {
//                boolean keepGoing;
//                do {
//                    keepGoing = await this.ReceivePumpCoreAsync().ConfigureAwait(false);
//                }
//                while (keepGoing && !this.shutdownCancellationSource.IsCancellationRequested);
//            }
//            catch (Exception e) when (!Fx.IsFatal(e)) {
//                RelayEventSource.Log.HandledExceptionAsWarning(this.listener, e);
//                exception = e;
//            }
//            finally {
//                this.onOffline(exception);
//            }
//        }

		// <summary>
		// Ensure we have a connected webSocket, listens for command messages, and
		// handles those messages.
		// </summary>
		// <returns>A boolean indicating whether or not the receive pump should keep
		// running.</returns>
//		private CompletableFuture<Boolean> receivePumpCoreAsync()
//        {
//            boolean keepGoing = true;
////            CancellationToken shutdownToken = this.shutdownCancellationSource.Token;
////            Task<WebSocket> connectTask = this.ensureConnectTask(shutdownToken);
//            try
//            {
//            	Session session = this.ensureConnectTask(null).get();
//            	if (session.isOpen()) {
//            		
//            	}
//            	
////                WebSocket webSocket = await connectTask.ConfigureAwait(false);
////                int totalBytesRead = 0;
//                do {
//                    var currentReadBuffer = new ArraySegment<byte>(this.receiveBuffer.Array, this.receiveBuffer.Offset + totalBytesRead, this.receiveBuffer.Count - totalBytesRead);
//                    WebSocketReceiveResult receiveResult = await webSocket.ReceiveAsync(currentReadBuffer, CancellationToken.None).ConfigureAwait(false);
//                    if (receiveResult.MessageType == WebSocketMessageType.Close)
//                    {
//                        await this.CloseOrAbortWebSocketAsync(
//                            connectTask, false, receiveResult.CloseStatus.Value, receiveResult.CloseStatusDescription, shutdownToken).ConfigureAwait(false);
//                        if (this.listener.closeCalled)
//                        {
//                            // This is the cloud service responding to our clean shutdown.
//                            keepGoing = false;
//                        }
//                        else
//                        {
//                            keepGoing = this.OnDisconnect(new ConnectionLostException(receiveResult.CloseStatus.Value + ": " + receiveResult.CloseStatusDescription));
//                        }
//
//                        break;
//                    }
//
//                    totalBytesRead += receiveResult.Count;
//                    if (receiveResult.EndOfMessage)
//                    {
//                        var commandBuffer = new ArraySegment<byte>(this.receiveBuffer.Array, this.receiveBuffer.Offset, totalBytesRead);
//                        await this.listener.OnCommandAsync(commandBuffer, webSocket).ConfigureAwait(false);
//                        totalBytesRead = 0;
//                    }
//                }
//                while (!shutdownToken.IsCancellationRequested);
//            }
//            catch (Exception exception) when (!Fx.IsFatal(exception))
//            {
//                RelayEventSource.Log.HandledExceptionAsWarning(this.listener, exception);
//                await this.CloseOrAbortWebSocketAsync(connectTask, abort: true).ConfigureAwait(false);
//                keepGoing = this.OnDisconnect(WebSocketExceptionHelper.ConvertToRelayContract(exception, this.listener.TrackingContext));
//            }
//
//            return keepGoing;
//        }
		
		private void onOnline() {
			synchronized (this.thisLock) {
				if(this.isOnline) {
					return;
				}

				this.lastError = null;
				this.isOnline = true;
				this.connectDelayIndex = -1;
			}

			// TODO: trace
//			RelayEventSource.Log.Info(this.listener,"Online");
			if (this.onlineHandler != null ) {
				this.onlineHandler.accept(this, null);
			}
		}
		
		private void onMessage(String message) {
			onCommandAsync(message, this.webSocket);
		}

//		private void onOffline(Exception lastError) {
//			synchronized (this.thisLock) {
//				if (lastError!=null) {
//					this.lastError = lastError;
//				}
//				this.isOnline=false;
//			}
//
//			// TODO: trace
//			// Stop attempting to connect
////			RelayEventSource.Log.Info(this.listener,$"Offline. {this.listener.TrackingContext}");this.Offline?.Invoke(this,EventArgs.Empty);
//		}

		// Returns true if this control connection should attempt to reconnect after
		// this exception.
//		private boolean onDisconnect(Exception lastError) {
//			
//			synchronized (this.thisLock) {
//				this.lastError=lastError;
//				this.isOnline=false;
//
//				if (this.connectDelayIndex < CONNECTION_DELAY_INTERVALS.length - 1) {
//					this.connectDelayIndex++;
//				}
//			}
//
//			// Inspect the close status/description to see if this is a terminal case
//			// or we should attempt to reconnect.
//			boolean shouldReconnect = this.shouldReconnect(lastError);
//			
//			if(shouldReconnect && this.connectingHandler != null) {
//				this.connectingHandler.accept(this, null);
//			}
//
//			return shouldReconnect;
//		}

//		private boolean shouldReconnect(Exception exception) {
//			return (!(exception instanceof EndpointNotFoundException));
//		}

//		private Consumer<SecurityToken> onTokenRenewed(SecurityToken token) {
//            try {
//                var listenerCommand = new ListenerCommand { RenewToken = new ListenerCommand.RenewTokenCommand() };
//                listenerCommand.RenewToken.Token = eventArgs.Token.TokenString;
//
//                await this.SendCommandAndStreamAsync(listenerCommand, null, CancellationToken.None).ConfigureAwait(false);
//            }
//            catch (Exception exception) when (!Fx.IsFatal(exception)) {
//                RelayEventSource.Log.HandledExceptionAsWarning(this.listener, exception);
//            }
//        }
	}
}
