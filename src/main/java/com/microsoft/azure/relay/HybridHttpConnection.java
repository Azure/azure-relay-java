package com.microsoft.azure.relay;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.management.RuntimeErrorException;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;

import org.eclipse.jetty.http.HttpStatus;
import org.json.JSONObject;

import com.microsoft.azure.relay.AsyncLock.LockRelease;
import com.microsoft.azure.relay.ListenerCommand.RequestCommand;

public class HybridHttpConnection {
	private static final int MAX_CONTROL_CONNECTION_BODY_SIZE = 64 * 1024;
    private static final int BUFFER_SIZE = 8 * 1024;
    private final HybridConnectionListener listener;
    private final ClientWebSocket controlWebSocket;
    private final URI rendezvousAddress;
    private ClientWebSocket rendezvousWebSocket;
    private TrackingContext trackingContext;
    private Duration operationTimeout;
    
    private enum FlushReason { BUFFER_FULL, RENDEZVOUS_EXISTS, TIMER }

    public TrackingContext getTrackingContext() {
    	return this.trackingContext;
    }
    
    public Duration getOperationTimeout() {
    	return this.listener.getOperationTimeout();
    }
    
	protected HybridHttpConnection() {
		this.listener = null;
		this.controlWebSocket = null;
		this.rendezvousAddress = null;
	}
    
    private HybridHttpConnection(HybridConnectionListener listener, ClientWebSocket controlWebSocket, String rendezvousAddress) {
        this.listener = listener;
        this.controlWebSocket = controlWebSocket;
        this.trackingContext = this.getTrackingContext();
        try {
			this.rendezvousAddress = new URI(rendezvousAddress);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("the rendezvous address is invalid.");
		}
        // TODO: trace
//        RelayEventSource.Log.HybridHttpRequestStarting(this.trackingContext);
    }

	public CompletableFuture<Void> createAsync(HybridConnectionListener listener, ListenerCommand.RequestCommand requestCommand, ClientWebSocket controlWebSocket) {
    	HybridHttpConnection hybridHttpConnection = new HybridHttpConnection(listener, controlWebSocket, requestCommand.getAddress());

        // Do only what we need to do (receive any request body from control channel) and then let this Task complete.
        Boolean requestOverControlConnection = requestCommand.hasBody();
        CompletableFuture<RequestCommandAndStream> requestAndStream = CompletableFuture.completedFuture(new RequestCommandAndStream(requestCommand, null));
        if (requestOverControlConnection != null && requestOverControlConnection == true) {
            requestAndStream = hybridHttpConnection.receiveRequestBodyOverControlAsync(requestCommand);
        }

        // ProcessFirstRequestAsync runs without blocking the listener control connection:
        return requestAndStream.thenAccept((requestCommandAndStream) -> hybridHttpConnection.processFirstRequestAsync(requestCommandAndStream));
    }

//    @Override
//    public String toString() {
//        return nameof(HybridHttpConnection);
//    }
//
//    private TrackingContext getNewTrackingContext() {
//        Map<String, String> queryParameters = HybridConnectionUtil.parseQueryString(this.rendezvousAddress.getQuery());
//        String trackingId = queryParameters.get(HybridConnectionConstants.ID);
//
//        String path = this.rendezvousAddress.getPath();
//        if (path.startsWith(HybridConnectionConstants.HYBRIDCONNECTION_REQUEST_URI)) {
//            path = path.substring(HybridConnectionConstants.HYBRIDCONNECTION_REQUEST_URI.length());
//        }
//        URI logicalAddress = new URI("https", this.listener.getAddress().getHost(), path, null);
//
//        return TrackingContext.create(trackingId, logicalAddress);
//    }

    private CompletableFuture<Void> processFirstRequestAsync(RequestCommandAndStream requestAndStream) {
    	try {
    		ListenerCommand.RequestCommand requestCommand = requestAndStream.getRequestCommand();
            
            if (requestCommand.hasBody() == null) {
                // Need to rendezvous to get the real RequestCommand
                requestAndStream = this.receiveRequestOverRendezvousAsync().get();
            }
            this.invokeRequestHandler(requestAndStream);
        }
        catch (Exception e)
    	// TODO: when (!Fx.IsFatal(e))
        {
        	// TODO: trace
//            RelayEventSource.Log.HandledExceptionAsWarning(this.listener, e);
//            return this.closeAsync();
        }
    	return null;
    }

    private CompletableFuture<RequestCommandAndStream> receiveRequestBodyOverControlAsync(ListenerCommand.RequestCommand requestCommand) {
    	ByteBuffer requestStream = null;
    	
    	if (requestCommand.hasBody()) {
			CompletableFuture<ByteBuffer> receiveOverControlSocketTask = this.controlWebSocket.receiveMessageAsync();
			return receiveOverControlSocketTask.thenApply((receivedData) -> new RequestCommandAndStream(requestCommand, receivedData));
    	}
    	return CompletableFuture.completedFuture(new RequestCommandAndStream(requestCommand, requestStream));
    }

    private CompletableFuture<RequestCommandAndStream> receiveRequestOverRendezvousAsync() {
    	// A Rendezvous is required to get full request
    	try {
			this.ensureRendezvousAsync(this.getOperationTimeout()).get();
		} catch (InterruptedException | ExecutionException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
    	
		CompletableFuture<String> receiveCommandOverRendezvousTask = this.rendezvousWebSocket.receiveControlMessageAsync();
		CompletableFuture<ByteBuffer> receiveMessageOverRendezvousTask = this.rendezvousWebSocket.receiveMessageAsync();
		
		return receiveCommandOverRendezvousTask.thenApply((commandJson) -> {
			JSONObject jsonObj = new JSONObject(commandJson);
			ListenerCommand.RequestCommand realRequestCommand = new ListenerCommand(jsonObj).getRequest();
			ByteBuffer requestStream = null;
			 
			if (realRequestCommand != null && realRequestCommand.hasBody()) {
	        	// TODO: trace
//	            RelayEventSource.Log.HybridHttpReadRendezvousValue(this, "request body");
				try {
					requestStream = receiveMessageOverRendezvousTask.get();
				} catch (InterruptedException | ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			return new RequestCommandAndStream(realRequestCommand, requestStream);
		});
    }

    void invokeRequestHandler(RequestCommandAndStream requestAndStream) throws URISyntaxException {
        ListenerCommand.RequestCommand requestCommand = requestAndStream.getRequestCommand();
        String listenerAddress = this.listener.getAddress().toString();
        String requestTarget = requestCommand.getRequestTarget();
        URI requestUri = (listenerAddress.endsWith("/") || requestTarget.startsWith("/")) ? 
        		new URI(listenerAddress + requestTarget) : new URI(listenerAddress + "/" + requestTarget);
        
        RelayedHttpListenerContext listenerContext = new RelayedHttpListenerContext(
            this.listener,
            requestUri,
            requestCommand.getId(),
            requestCommand.getMethod(),
            requestCommand.getRequestHeaders());
        listenerContext.getRequest().setRemoteAddress(requestCommand.getRemoteEndpoint());
        listenerContext.getResponse().setStatusCode(HttpStatus.OK_200);
        listenerContext.getResponse().setOutputStream(new ResponseStream(this, listenerContext));

        // TODO: trace
//        RelayEventSource.Log.HybridHttpRequestReceived(listenerContext.TrackingContext, requestCommand.Method);

        ByteBuffer requestStream = requestAndStream.getStream();
        if (requestStream != null) {
            listenerContext.getRequest().setHasEntityBody(true);
            listenerContext.getRequest().setInputStream(requestStream);
        }

        Consumer<RelayedHttpListenerContext> requestHandler = this.listener.getRequestHandler();
        if (requestHandler != null) {
            try {
            	// TODO: trace
//                RelayEventSource.Log.HybridHttpInvokingUserRequestHandler();
                requestHandler.accept(listenerContext);
            }
            catch (Exception userException) 
//            when (!Fx.IsFatal(userException))
            {
            	// TODO: trace
//                RelayEventSource.Log.HandledExceptionAsWarning(this, userException);
                listenerContext.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500);
                // TODO: string resource manager
//                listenerContext.getResponse().setStatusDescription(this.trackingContext.EnsureTrackableMessage(SR.RequestHandlerException));
                listenerContext.getResponse().close();
                return;
            }
        }
        else {
        	// TODO: trace
//            RelayEventSource.Log.HybridHttpConnectionMissingRequestHandler();
            listenerContext.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED_501);
            // TODO: string resource manager
//            listenerContext.getResponse().setStatusDescription(this.trackingContext.EnsureTrackableMessage(SR.RequestHandlerMissing));
            listenerContext.getResponse().close();
        }
    }

    private CompletableFuture<Void> sendResponseAsync(ListenerCommand.ResponseCommand responseCommand, ByteBuffer responseBodyStream, Duration timeout) {
        if (this.rendezvousWebSocket == null) {
        	// TODO: tracing
//            RelayEventSource.Log.HybridHttpConnectionSendResponse(this.getTrackingContext(), "control", responseCommand.StatusCode);
        	ListenerCommand listenerCommand = new ListenerCommand(null);
        	listenerCommand.setResponse(responseCommand);
            return this.listener.sendControlCommandAndStreamAsync(listenerCommand, responseBodyStream, timeout);
        }
        else {
        	// TODO: tracing
//            RelayEventSource.Log.HybridHttpConnectionSendResponse(this.TrackingContext, "rendezvous", responseCommand.StatusCode);
            try {
				this.ensureRendezvousAsync(timeout).get();
			} catch (InterruptedException | ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

            ListenerCommand listenerCommand = new ListenerCommand(null);
            listenerCommand.setResponse(responseCommand);
            String command = listenerCommand.getResponse().toJsonString();

            // We need to respond over the rendezvous connection
            CompletableFuture<Void> sendCommandTask = this.rendezvousWebSocket.sendCommandAsync(command, timeout);
            if (responseCommand.hasBody() && responseBodyStream != null) {
            	return sendCommandTask.thenCompose((result) -> {
            		return this.rendezvousWebSocket.sendAsync(responseBodyStream.array(), timeout);
            	});
            }
            return sendCommandTask;
        }
    }

    private CompletableFuture<Void> sendBytesOverRendezvousAsync(ByteBuffer buffer, Duration timeout) {
    	// TODO: trace
//        RelayEventSource.Log.HybridHttpConnectionSendBytes(this.TrackingContext, buffer.Count);
        return (buffer != null) ? this.rendezvousWebSocket.sendAsync(buffer.array(), timeout) : CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> ensureRendezvousAsync(Duration timeout) {
        if (this.rendezvousWebSocket == null) {
        	// TODO: trace
//            RelayEventSource.Log.HybridHttpCreatingRendezvousConnection(this.TrackingContext);
            // TODO: proxy
//            clientWebSocket.Options.Proxy = this.listener.Proxy;
            this.rendezvousWebSocket = new ClientWebSocket();
            return this.rendezvousWebSocket.connectAsync(this.rendezvousAddress, timeout);
        }
        return CompletableFuture.completedFuture(null);
    }

//    private CompletableFuture<Void> closeAsync() {
//    	CompletableFuture<Void> closeRendezvousTask = (this.rendezvousWebSocket != null) ? 
//    			this.rendezvousWebSocket.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "NormalClosure"), null) : CompletableFuture.completedFuture(null);
////    	CompletableFuture<Void> closeControlTask = this.controlWebSocket.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "NormalClosure"), null);
//        	// TODO: trace
////            RelayEventSource.Log.ObjectClosing(this);
////            RelayEventSource.Log.ObjectClosed(this);
////        return CompletableFuture.allOf(closeControlTask, closeRendezvousTask);
//    			return closeRendezvousTask;
//    }
    
    private void closeRendezvous() {
    	this.rendezvousWebSocket.close(new CloseReason(CloseCodes.NORMAL_CLOSURE, "NormalClosure"));
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

    final class ResponseStream extends OutputStream {
        private static final long WRITE_BUFFER_FLUSH_TIMEOUT = 200000;
        private final HybridHttpConnection connection;
        private final RelayedHttpListenerContext context;
        private final AsyncLock asyncLock;
        private boolean closed;
        private ByteBuffer writeBufferStream;
        private Timer writeBufferFlushTimer;
        private boolean responseCommandSent;
        private TrackingContext trackingContext;
        private int writeTimeout;

        public TrackingContext getTrackingContext() {
			return trackingContext;
		}
		public int getWriteTimeout() {
			return writeTimeout;
		}
		public void setWriteTimeout(int writeTimeout) {
			this.writeTimeout = writeTimeout;
		}

		public ResponseStream(HybridHttpConnection connection, RelayedHttpListenerContext context) {
            this.connection = connection;
            this.context = context;
            this.trackingContext = context.getTrackingContext();
            this.writeTimeout = TimeoutHelper.toMillis(this.connection.getOperationTimeout());
            this.asyncLock = new AsyncLock();
        }

        // The caller of this method must have acquired this.asyncLock
        CompletableFuture<Void> flushCoreAsync(FlushReason reason, Duration timeout) {
        	// TODO: trace
//            RelayEventSource.Log.HybridHttpResponseStreamFlush(this.TrackingContext, reason.ToString());
        	
            if (!this.responseCommandSent) {
            	ListenerCommand.ResponseCommand responseCommand = createResponseCommand(this.context);
                responseCommand.setBody(true);

                // At this point we have no choice but to rendezvous send the response command over the rendezvous connection
                CompletableFuture<Void> sendResponseTask = this.connection.ensureRendezvousAsync(timeout).thenComposeAsync((result) -> {
                	return this.connection.sendResponseAsync(responseCommand, null, timeout);
                })
                .thenRun(() -> this.responseCommandSent = true);
                
                // When there is no request message body
                if (this.writeBufferStream != null && this.writeBufferStream.position() > 0) {
                    return CompletableFuture.allOf(sendResponseTask, 
                    		this.connection.sendBytesOverRendezvousAsync(this.writeBufferStream, timeout).thenRun(() -> {
                        	this.writeBufferStream.clear();
                        	if (this.writeBufferFlushTimer != null) {
                        		this.writeBufferFlushTimer.cancel();
                        	}
                    }));
                }
                return sendResponseTask;
            }
            return CompletableFuture.completedFuture(null);
        }

		@Override
		public void write(int b) throws IOException {
			try {
				this.writeAsync(new byte[] {(byte) b}, 0, 1).get();
			} catch (InterruptedException | ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		public void write(byte[] bytes) {
			try {
				this.writeAsync(bytes, 0, bytes.length).get();
			} catch (InterruptedException | ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		public void write(String text) {
			try {
				this.writeAsync(text.getBytes(), 0, text.length()).get();
			} catch (InterruptedException | ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
        public CompletableFuture<Void> writeAsync(byte[] array, int offset, int count) {
        	// TODO: trace
//            RelayEventSource.Log.HybridHttpResponseStreamWrite(this.TrackingContext, count);
        	Duration timeout = Duration.ofMillis(this.writeTimeout);
        	try {
				LockRelease lockRelease = this.asyncLock.lockAsync(timeout).get();
				CompletableFuture<Void> flushCoreTask = null;
				
	    		if (!this.responseCommandSent) {
	                FlushReason flushReason;
	                if (this.connection.rendezvousWebSocket != null) {
	                    flushReason = FlushReason.RENDEZVOUS_EXISTS;
	                }
	                else {
	                    int bufferedCount = this.writeBufferStream != null ? this.writeBufferStream.position() : 0;
	                    if (count + bufferedCount <= MAX_CONTROL_CONNECTION_BODY_SIZE) {
	                    	
	                        // There's still a chance we might be able to respond over the control connection, accumulate bytes
	                        if (this.writeBufferStream == null) {
	                            int initialStreamSize = Math.min(count, MAX_CONTROL_CONNECTION_BODY_SIZE);
	                            this.writeBufferStream = ByteBuffer.allocate(initialStreamSize);
	                            this.writeBufferFlushTimer = new Timer();
	                            
	                            this.writeBufferFlushTimer.schedule(new TimerTask() {	
									@Override
									public void run() {
										onWriteBufferFlushTimer();
									}
								}, WRITE_BUFFER_FLUSH_TIMEOUT, Long.MAX_VALUE);		
	                            		
	                        }
	                        this.writeBufferStream.put(array, offset, count);
	                        lockRelease.release();
	                        return CompletableFuture.completedFuture(null);
	                    }
	                    flushReason = FlushReason.BUFFER_FULL;
	                }

	                // FlushCoreAsync will rendezvous, send the responseCommand, and any writeBufferStream bytes
	                flushCoreTask = this.flushCoreAsync(flushReason, timeout);
	    		}
	    		
	    		ByteBuffer buffer = ByteBuffer.wrap(array, offset, count);
	    		if (flushCoreTask == null) {
	    			flushCoreTask = CompletableFuture.completedFuture(null);
	    		}
	    		lockRelease.release();
	    		return flushCoreTask.thenCompose(result -> this.connection.sendBytesOverRendezvousAsync(buffer, timeout));
			} 
        	catch (Exception e) {
				// TODO Auto-generated catch block
				throw new RuntimeException(e.getMessage());
			}
        }

        @Override
        public String toString() {
            return this.connection.toString() + "+" + "ResponseStream";
        }

//        protected override void Dispose(bool disposing)
//        {
//            try
//            {
//                if (disposing && !this.closed)
//                {
//                    this.CloseAsync().ConfigureAwait(false).GetAwaiter().GetResult();
//                }
//            }
//            finally
//            {
//                base.Dispose(disposing);
//            }
//        }

        public CompletableFuture<Void> closeAsync() {
            if (this.closed) {
                return CompletableFuture.completedFuture(null);
            }
            
            CompletableFuture<Void> closeTask;
            CompletableFuture<Void> sendTask = null;
            try {
            	// TODO: trace
//                RelayEventSource.Log.ObjectClosing(this);

            	Duration timeout = Duration.ofMillis(this.writeTimeout);
            	// TODO: asynclock if needed
//            	this.asyncLock.lockAsync(timeout).thenAccept((lockRelease) -> {
            		if (!this.responseCommandSent) {
	                    ListenerCommand.ResponseCommand responseCommand = createResponseCommand(this.context);
	                    if (this.writeBufferStream != null) {
	                        responseCommand.setBody(true);
	                        this.writeBufferStream.position(0);
	                    }
	
	                    // Don't force any rendezvous now
	                    sendTask = this.connection.sendResponseAsync(responseCommand, this.writeBufferStream, timeout);
	                    this.responseCommandSent = true;
	                    if (this.writeBufferFlushTimer != null) {
		                    this.writeBufferFlushTimer.cancel();
	                    }
	                }
	                else {
	                	sendTask = this.connection.sendBytesOverRendezvousAsync(null, timeout);
	                }
	            	// TODO: trace
	//                RelayEventSource.Log.ObjectClosed(this);
            		
//            		lockRelease.release();
//            	});
            }
            catch (Exception e) {
//           TODO: when (!Fx.IsFatal(e)) {
//                RelayEventSource.Log.ThrowingException(e, this);
                throw new RuntimeException(e.getMessage());
            }
            finally {
//                closeTask = sendTask.thenCompose((result) -> this.connection.closeAsync());
                this.closed = true;
            }
//            return closeTask;
            return sendTask.thenCompose((result) -> {
            	closeRendezvous();
				return CompletableFuture.completedFuture(null);
			});
        }

        CompletableFuture<Void> onWriteBufferFlushTimer() {
            return this.asyncLock.lockAsync().thenAccept((lockRelease) -> {
                this.flushCoreAsync(FlushReason.TIMER, Duration.ofSeconds(this.writeTimeout));
                lockRelease.release();
            });
        }
    }

    public final class RequestCommandAndStream {
        private ListenerCommand.RequestCommand requestCommand;
        private ByteBuffer stream;
        
        public ListenerCommand.RequestCommand getRequestCommand() {
			return requestCommand;
		}
		public void setRequestCommand(ListenerCommand.RequestCommand requestCommand) {
			this.requestCommand = requestCommand;
		}
		public ByteBuffer getStream() {
			return stream;
		}
		public void setStream(ByteBuffer stream) {
			this.stream = stream;
		}

		public RequestCommandAndStream(ListenerCommand.RequestCommand requestCommand, ByteBuffer stream) {
            this.requestCommand = requestCommand;
            this.stream = stream;
        }
    }
}

