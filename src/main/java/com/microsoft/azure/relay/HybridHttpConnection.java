package com.microsoft.azure.relay;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;

import org.eclipse.jetty.http.HttpStatus;
import org.json.JSONObject;

class HybridHttpConnection implements RelayTraceSource {
	private static final int MAX_CONTROL_CONNECTION_BODY_SIZE = 64 * 1024;
	private final AutoShutdownScheduledExecutor executor;
	private final HybridConnectionListener listener;
	private final ClientWebSocket controlWebSocket;
	private final URI rendezvousAddress;
	private ClientWebSocket rendezvousWebSocket;
	private TrackingContext trackingContext;
	private ListenerCommand.RequestCommand requestCommand;
	private String cachedString;

	private enum FlushReason {
		BUFFER_FULL, RENDEZVOUS_EXISTS, TIMER
	}
	
	private HybridHttpConnection(HybridConnectionListener listener, ClientWebSocket controlWebSocket,
			String rendezvousAddress, AutoShutdownScheduledExecutor executor) throws URISyntaxException {
		this.executor = executor;
		this.listener = listener;
		this.controlWebSocket = controlWebSocket;
		this.rendezvousAddress = new URI(rendezvousAddress);
		this.trackingContext = this.getNewTrackingContext();

		RelayLogger.logEvent("httpRequestStarting", this);
	}
	
	public TrackingContext getTrackingContext() {
		return this.trackingContext;
	}
	
	public Duration getOperationTimeout() {
		return this.listener.getOperationTimeout();
	}

	static CompletableFuture<Void> createAsync(HybridConnectionListener listener,
			ListenerCommand.RequestCommand requestCommand, ClientWebSocket controlWebSocket) {
		
		HybridHttpConnection hybridHttpConnection;
		try {
			hybridHttpConnection = new HybridHttpConnection(listener, controlWebSocket, requestCommand.getAddress(), HybridConnectionListener.EXECUTOR);
		} catch (URISyntaxException e) {
			return CompletableFutureUtil.fromException(e);
		}

		// Do only what we need to do (receive any request body from control channel) and then let this Task complete.
		Boolean requestOverControlConnection = requestCommand.hasBody();
		if (requestOverControlConnection != null && requestOverControlConnection == true) {
			return hybridHttpConnection.receiveRequestBodyOverControlAsync(requestCommand)
					.thenAccept((requestAndStream) -> hybridHttpConnection.processFirstRequestAsync(requestAndStream));
		}

		// ProcessFirstRequestAsync runs without blocking the listener control connection:
		return hybridHttpConnection.processFirstRequestAsync(new RequestCommandAndStream(requestCommand, null));
	}

    @Override
    public String toString() {
		if (this.cachedString == null) {
			this.cachedString = this.getClass().getSimpleName() + "(" + this.trackingContext + ")";
		}
		return this.cachedString;
    }

    private TrackingContext getNewTrackingContext() throws URISyntaxException {
        Map<String, String> queryParameters = HybridConnectionUtil.parseQueryString(this.rendezvousAddress.getQuery());
        String trackingId = queryParameters.get(HybridConnectionConstants.ID);

        String path = this.rendezvousAddress.getPath();
        if (path.startsWith(HybridConnectionConstants.HYBRIDCONNECTION_REQUEST_URI)) {
            path = path.substring(HybridConnectionConstants.HYBRIDCONNECTION_REQUEST_URI.length());
        }
        URI logicalAddress = new URI("https", this.listener.getAddress().getHost(), path, null);

        return TrackingContext.create(trackingId, logicalAddress);
    }

	private CompletableFuture<Void> processFirstRequestAsync(RequestCommandAndStream requestAndStream) {
		CompletableFuture<Void> processTask = new CompletableFuture<Void>();
		ListenerCommand.RequestCommand requestCommand = requestAndStream.getRequestCommand();

		if (requestCommand.hasBody() == null) {
			// Need to rendezvous to get the real RequestCommand
			processTask = this.receiveRequestOverRendezvousAsync().thenAccept((realRequestAndStream) -> {
				this.invokeRequestHandler(realRequestAndStream);
			});
		} else {
			processTask = CompletableFuture.runAsync(() -> this.invokeRequestHandler(requestAndStream));
		}
		
		return processTask.handle((result, ex) -> {
			return ex;
		}).thenCompose(ex -> {
			if (ex != null) {
				return CompletableFutureUtil.fromException(
					RelayLogger.throwingException(ex, this, TraceLevel.WARNING));
			}
			return CompletableFuture.completedFuture(null);
		});
	}

	private CompletableFuture<RequestCommandAndStream> receiveRequestBodyOverControlAsync(
			ListenerCommand.RequestCommand requestCommand) {
		ByteArrayInputStream requestStream = null;

		if (requestCommand.hasBody()) {
			return this.controlWebSocket.readBinaryAsync().thenApply((receivedData) -> {
				return new RequestCommandAndStream(requestCommand, new ByteArrayInputStream(receivedData.array()));
			});
		}

		return CompletableFuture.completedFuture(new RequestCommandAndStream(requestCommand, requestStream));
	}

	private CompletableFuture<RequestCommandAndStream> receiveRequestOverRendezvousAsync() throws CompletionException {

		return this.ensureRendezvousAsync(this.getOperationTimeout())
			.thenCompose(rendezvousResult -> this.rendezvousWebSocket.readTextAsync())
			.thenCompose(commandJson -> {
				JSONObject jsonObj = new JSONObject(commandJson);
				this.requestCommand = new ListenerCommand(jsonObj).getRequest();
	
				if (this.requestCommand != null && this.requestCommand.hasBody()) {
					RelayLogger.logEvent("httpReadRendezvous", this, "request body");
					return this.rendezvousWebSocket.readBinaryAsync();
				}
	
				return CompletableFuture.completedFuture(null);
			})
			.thenApply(buffer -> new RequestCommandAndStream(this.requestCommand, new ByteArrayInputStream(buffer.array())));
	}

	void invokeRequestHandler(RequestCommandAndStream requestAndStream) {
		ListenerCommand.RequestCommand requestCommand = requestAndStream.getRequestCommand();
		URI listenerAddress = this.listener.getAddress();
		String requestTarget = requestCommand.getRequestTarget();
		URI requestUri = null;

		try {
			String listenerAddressStr = listenerAddress.toString();
			requestTarget = requestTarget.replaceFirst(listenerAddress.getPath(), "");
			requestUri = (listenerAddressStr.endsWith("/") || requestTarget.startsWith("/"))
					? new URI(listenerAddressStr + requestTarget)
					: new URI(listenerAddressStr + "/" + requestTarget);
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}

		RelayedHttpListenerContext listenerContext = new RelayedHttpListenerContext(this.listener, requestUri,
				requestCommand.getId(), requestCommand.getMethod(), requestCommand.getRequestHeaders());
		listenerContext.getRequest().setRemoteEndPoint(requestCommand.getRemoteEndpoint());
		listenerContext.getResponse().setStatusCode(HttpStatus.OK_200);
		listenerContext.getResponse().setOutputStream(new ResponseStream(this, listenerContext));

		RelayLogger.logEvent("httpRequestReceived", this, requestCommand.getMethod());
		
		ByteArrayInputStream requestStream = requestAndStream.getStream();
		if (requestStream != null) {
			listenerContext.getRequest().setHasEntityBody(true);
			listenerContext.getRequest().setInputStream(requestStream);
		}

		Consumer<RelayedHttpListenerContext> requestHandler = this.listener.getRequestHandler();
		if (requestHandler != null) {
			try {
				RelayLogger.logEvent("httpInvokeUserHandler", this);
				requestHandler.accept(listenerContext);
			} catch (Exception userException) {
				RelayLogger.throwingException(userException, this);
				listenerContext.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500);
                listenerContext.getResponse().setStatusDescription(
                		"The listener RequestHandler threw an exception. See listener logs for more details.");
				listenerContext.getResponse().close();
				return;
			}
		} else {
			RelayLogger.logEvent("httpMissingRequestHandler", this);
			listenerContext.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED_501);
            listenerContext.getResponse().setStatusDescription("The listener RequestHandler has not been configured.");
			listenerContext.getResponse().close();
		}
	}

	private CompletableFuture<Void> sendResponseAsync(ListenerCommand.ResponseCommand responseCommand,
			ByteBuffer responseBodyBuffer, Duration timeout) throws CompletionException {
		if (this.rendezvousWebSocket == null) {
			RelayLogger.logEvent("httpSendResponse", this, "control", String.valueOf(responseCommand.getStatusCode()));
			ListenerCommand listenerCommand = new ListenerCommand(null);
			listenerCommand.setResponse(responseCommand);
			return this.listener.sendControlCommandAndStreamAsync(listenerCommand, responseBodyBuffer, timeout)
					.thenRun(() -> RelayLogger.logEvent("httpSendResponseFinished", this, "control", String.valueOf(responseCommand.getStatusCode())));
		} else {
			TimeoutHelper timeRemaining = new TimeoutHelper(timeout);
			RelayLogger.logEvent("httpSendResponse", this, "rendezvous", String.valueOf(responseCommand.getStatusCode()));

			ListenerCommand listenerCommand = new ListenerCommand(null);
			listenerCommand.setResponse(responseCommand);
			String command = listenerCommand.getResponse().toJsonString();

			// We need to respond over the rendezvous connection
			CompletableFuture<Void> sendCommandTask = this.ensureRendezvousAsync(timeRemaining.remainingTime())
				.thenCompose($void -> this.rendezvousWebSocket.writeAsync(command, timeRemaining.remainingTime(), true, WriteMode.TEXT))
				.thenAccept(bytesWritten -> {
					RelayLogger.logEvent("httpSendResponseFinished", this, "rendezvous", String.valueOf(responseCommand.getStatusCode()));
				});
			
			if (responseCommand.hasBody() && responseBodyBuffer != null) {
				return sendCommandTask.thenCompose($void -> {
					int bytesToWrite = responseBodyBuffer.remaining();
					return sendBytesOverRendezvousAsync(responseBodyBuffer, timeRemaining.remainingTime())
						.thenRun(() -> {
							RelayLogger.logEvent("httpSendingBytes", this, String.valueOf(bytesToWrite));
						});
				});
			}
			return sendCommandTask;
		}
	}

	private CompletableFuture<Void> sendBytesOverRendezvousAsync(ByteBuffer buffer, Duration timeout) {
		if (buffer == null) {
			return CompletableFuture.completedFuture(null);
		}
		return this.rendezvousWebSocket.writeAsync(buffer, timeout, false, WriteMode.BINARY).thenAccept(nullResult -> {
			RelayLogger.logEvent("httpSendingBytes", this, String.valueOf(buffer.remaining()));
		});
	}

	private CompletableFuture<Void> ensureRendezvousAsync(Duration timeout) throws CompletionException {
		if (this.rendezvousWebSocket == null) {
			RelayLogger.logEvent("httpCreateRendezvous", this);
			this.rendezvousWebSocket = new ClientWebSocket(this.trackingContext, this.executor);
			return this.rendezvousWebSocket.connectAsync(this.rendezvousAddress, timeout);
		}
		return CompletableFuture.completedFuture(null);
	}

	private CompletableFuture<Void> closeRendezvousAsync() {
		if (this.rendezvousWebSocket != null) {
			RelayLogger.logEvent("closing", this);
			return this.rendezvousWebSocket
					.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "NormalClosure"))
					.thenRun(() -> RelayLogger.logEvent("closed", this));
		} else {
			return CompletableFuture.completedFuture(null);
		}

	}

	static ListenerCommand.ResponseCommand createResponseCommand(RelayedHttpListenerContext listenerContext) {
		RelayedHttpListenerResponse response = listenerContext.getResponse();
		ListenerCommand listenerCommand = new ListenerCommand(null);
		ListenerCommand.ResponseCommand responseCommand = listenerCommand.new ResponseCommand();
		responseCommand.setStatusCode((int) response.getStatusCode());
		responseCommand.setStatusDescription(response.getStatusDescription());
		responseCommand.setRequestId(listenerContext.getTrackingContext().getTrackingId());
		response.getHeaders().forEach((key, val) -> responseCommand.getResponseHeaders().put(key, val));

		return responseCommand;
	}

	public final class ResponseStream extends OutputStream {
		static final long WRITE_BUFFER_FLUSH_TIMEOUT_MILLIS = 2000;
		private final HybridHttpConnection connection;
		private final RelayedHttpListenerContext context;
		private final AsyncLock asyncLock;
		private boolean closed;
		private ByteBuffer writeBufferStream;
		private Timer writeBufferFlushTimer;
		private boolean responseCommandSent;
		private final TrackingContext trackingContext;
		private Duration writeTimeout;

		ResponseStream(HybridHttpConnection connection, RelayedHttpListenerContext context) {
			this.connection = connection;
			this.context = context;
			this.trackingContext = context.getTrackingContext();
			this.writeTimeout = this.connection.getOperationTimeout();
			this.asyncLock = new AsyncLock(HybridConnectionListener.EXECUTOR);
		}
		
		public TrackingContext getTrackingContext() {
			return trackingContext;
		}

		public Duration getWriteTimeout() {
			return writeTimeout;
		}

		public void setWriteTimeout(Duration writeTimeout) {
			this.writeTimeout = writeTimeout;
		}

        // Nothing to do here. Either:
        // 1. We're still buffering data to see if it will all fit into a single response on the control connection
        // - Or -
        // 2. We've got a rendezvous and each Write[Async] call flushes to the websocket immediately
		@Override
		public void flush() throws IOException { }
		
		// The caller of this method must have acquired this.asyncLock
		CompletableFuture<Void> flushCoreAsync(FlushReason reason, Duration timeout) throws CompletionException {
			RelayLogger.logEvent("httpResponseStreamFlush", this, reason.toString());
			TimeoutHelper timeoutHelper = new TimeoutHelper(timeout);
			
			if (!this.responseCommandSent) {
				ListenerCommand.ResponseCommand responseCommand = createResponseCommand(this.context);
				responseCommand.setBody(true);

				// At this point we have no choice but to rendezvous send the response command
				// over the rendezvous connection
				CompletableFuture<Void> sendResponseTask = this.connection.ensureRendezvousAsync(timeoutHelper.remainingTime())
						.thenComposeAsync($void -> {
							return this.connection.sendResponseAsync(responseCommand, null, timeoutHelper.remainingTime());
						})
						.thenRun(() -> this.responseCommandSent = true);

				// When there is no request message body
				if (this.writeBufferStream != null && this.writeBufferStream.position() > 0) {
					return sendResponseTask.thenCompose($void -> {
						// Get a new buffer backed by the non empty segment of the write buffer array
						this.writeBufferStream.flip();
						return this.connection.sendBytesOverRendezvousAsync(this.writeBufferStream, timeoutHelper.remainingTime());
					})
					.thenRun(() -> {
						this.writeBufferStream.clear();
						if (this.writeBufferFlushTimer != null) {
							this.writeBufferFlushTimer.cancel();
						}
					});
				}
				return sendResponseTask;
			}
			return CompletableFuture.completedFuture(null);
		}

		/**
		 * Writes the specified byte to this response stream.
		 */
		@Override
		public void write(int b) throws IOException {
			this.write(new byte[] { (byte) b }, 0, 1);
		}

		/**
		 * Writes b.length bytes from the specified byte array to this response stream.
		 */
		@Override
		public void write(byte[] b) throws IOException {
			this.write(b, 0, b.length);
		}
		
		/**
		 * Writes len bytes from the specified byte array starting at offset off to this response stream.
		 */
		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			try {
				this.writeAsync(b, off, len).join();
			} catch (CompletionException e) {
				throw new IOException(e.getCause());
			}
		}

		/**
		 * Writes len bytes from the specified byte array starting at offset off to this response stream concurrently.
		 */
		public CompletableFuture<Void> writeAsync(byte[] b, int off, int len) {
			RelayLogger.logEvent("httpResponseStreamWrite", this, String.valueOf(len));
			this.context.getResponse().setReadonly();
			return this.asyncLock.acquireThenCompose(this.writeTimeout, () -> {
				CompletableFuture<Void> flushCoreTask = null;

				if (!this.responseCommandSent) {
					FlushReason flushReason;
					if (this.connection.rendezvousWebSocket != null) {
						flushReason = FlushReason.RENDEZVOUS_EXISTS;
					} else {
						int bufferedCount = this.writeBufferStream != null ? this.writeBufferStream.position() : 0;
						if (len + bufferedCount <= MAX_CONTROL_CONNECTION_BODY_SIZE) {

							// There's still a chance we might be able to respond over the control
							// connection, accumulate bytes
							if (this.writeBufferStream == null) {
								this.writeBufferStream = ByteBuffer.allocate(MAX_CONTROL_CONNECTION_BODY_SIZE);
								this.writeBufferFlushTimer = new Timer();

								this.writeBufferFlushTimer.schedule(new TimerTask() {
									@Override
									public void run() {
										onWriteBufferFlushTimer();
									}
								}, WRITE_BUFFER_FLUSH_TIMEOUT_MILLIS, Long.MAX_VALUE);

							}
							this.writeBufferStream.put(b, off, len);
							return CompletableFuture.completedFuture(null);
						}
						flushReason = FlushReason.BUFFER_FULL;
					}

					// FlushCoreAsync will rendezvous, send the responseCommand, and any
					// writeBufferStream bytes
					flushCoreTask = this.flushCoreAsync(flushReason, this.writeTimeout);
				}

				ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
				if (flushCoreTask == null) {
					flushCoreTask = CompletableFuture.completedFuture(null);
				}

				return flushCoreTask.thenCompose(result -> {
					return this.connection.sendBytesOverRendezvousAsync(buffer, this.writeTimeout);
				});
			});
		}

		@Override
		public String toString() {
			return this.connection.toString() + "+" + "ResponseStream";
		}

		@Override
		public void close() throws IOException {
			try {
				this.closeAsync().join();
			} catch (CompletionException e) {
				throw new IOException(e.getCause());
			}
		}
		
		public CompletableFuture<Void> closeAsync() {
			if (this.closed) {
				return CompletableFuture.completedFuture(null);
			}
			RelayLogger.logEvent("closing", this);

			return this.asyncLock.acquireThenCompose(this.writeTimeout, () -> {
				CompletableFuture<Void> sendTask = null;
				if (!this.responseCommandSent) {
					ListenerCommand.ResponseCommand responseCommand = createResponseCommand(this.context);
					if (this.writeBufferStream != null) {
						responseCommand.setBody(true);
						this.writeBufferStream.flip();
					}

					// Don't force any rendezvous now
					sendTask = this.connection.sendResponseAsync(responseCommand, this.writeBufferStream, this.writeTimeout);
					this.responseCommandSent = true;
					if (this.writeBufferFlushTimer != null) {
						this.writeBufferFlushTimer.cancel();
					}
				} else {
					sendTask = this.connection.sendBytesOverRendezvousAsync(null, this.writeTimeout);
				}

				return sendTask.thenCompose((result) -> {
					if (this.writeBufferStream != null) {
						this.writeBufferStream.position(0);
					}
					this.closed = true;
					return closeRendezvousAsync();
				});
			});
		}

		CompletableFuture<Void> onWriteBufferFlushTimer() {
			return this.asyncLock.acquireThenCompose(this.writeTimeout, () -> {
				return this.flushCoreAsync(FlushReason.TIMER, this.writeTimeout);
			});
		}
	}
}
