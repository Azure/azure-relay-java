package com.microsoft.azure.relay;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
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
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpStatus;
import org.json.JSONObject;

public class HybridHttpConnection {
	private static final int MAX_CONTROL_CONNECTION_BODY_SIZE = 8 * 1024;
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
        boolean requestOverControlConnection = requestCommand.hasBody();
        CompletableFuture<RequestCommandAndStream> requestAndStream = CompletableFuture.completedFuture(new RequestCommandAndStream(requestCommand, null));
        if (requestOverControlConnection) {
            requestAndStream = hybridHttpConnection.receiveRequestBodyOverControlAsync(requestCommand);
        }

        // ProcessFirstRequestAsync runs without blocking the listener control connection:
        return CompletableFuture.runAsync(() -> hybridHttpConnection.processFirstRequestAsync(requestAndStream.get()));
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
            
            if (!requestCommand.hasBody()) {
                // Need to rendezvous to get the real RequestCommand
//                requestAndStream = this.receiveRequestOverRendezvousAsync();
            }
            this.InvokeRequestHandler(requestAndStream);
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
            // We need to buffer this body stream so we can let go of the listener control connection
            requestStream = ByteBuffer.allocate(BUFFER_SIZE);
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            
            var readResult = WebSocketUtility.readMessageAsync(this.controlWebSocket, buffer, requestStream, cts.Token).ConfigureAwait(false);
            if (readResult.MessageType == WebSocketMessageType.Close)
            {
                throw RelayEventSource.Log.ThrowingException(new InvalidOperationException(SR.EntityClosedOrAborted), this);
            }
            else if (readResult.MessageType != WebSocketMessageType.Binary)
            {
                throw RelayEventSource.Log.ThrowingException(
                    new ProtocolViolationException(SR.GetString(SR.InvalidType, WebSocketMessageType.Binary, readResult.MessageType)), this);
            }

            requestStream.Position = 0;
        }
        return CompletableFuture.completedFuture(new RequestCommandAndStream(requestCommand, requestStream));
    }

    private CompletableFuture<RequestCommandAndStream> receiveRequestOverRendezvousAsync() {
    	// A Rendezvous is required to get full request
    	this.ensureRendezvousAsync(this.getOperationTimeout());

    	// TODO: trace
//        RelayEventSource.Log.HybridHttpReadRendezvousValue(this, "request command");
//        BufferedReader reader = new BufferedReader(reader, 0);
        ListenerCommand.RequestCommand requestCommand = null;
        
//        using (var rendezvousCommandStream = new WebSocketMessageStream(this.rendezvousWebSocket, this.OperationTimeout))
//        {
//            requestCommand = ListenerCommand.ReadObject(rendezvousCommandStream).Request;
//            if (rendezvousCommandStream.MessageType == WebSocketMessageType.Close)
//            {
//                throw RelayEventSource.Log.ThrowingException(new InvalidOperationException(SR.EntityClosedOrAborted), this);
//            }
//            else if (rendezvousCommandStream.MessageType != WebSocketMessageType.Text)
//            {
//                throw RelayEventSource.Log.ThrowingException(
//                    new ProtocolViolationException(SR.GetString(SR.InvalidType, WebSocketMessageType.Text, rendezvousCommandStream.MessageType)), this);
//            }
//            else if (requestCommand == null)
//            {
//                throw RelayEventSource.Log.ThrowingException(new ProtocolViolationException(SR.GetString(SR.InvalidType, "request", "{unknown}")), this);
//            }
//        }

        ByteBuffer requestStream = null;
        if (requestCommand.hasBody()) {
        	// TODO: trace
//            RelayEventSource.Log.HybridHttpReadRendezvousValue(this, "request body");
            requestStream = ByteBuffer.allocate(BUFFER_SIZE);
//            new WebSocketMessageStream(this.rendezvousWebSocket, this.OperationTimeout);
        }

        return CompletableFuture.completedFuture(new RequestCommandAndStream(requestCommand, requestStream));
    }

    void InvokeRequestHandler(RequestCommandAndStream requestAndStream) {
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
        if (requestStream != null)
        {
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
        else
        {
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
            CompletableFuture<Void> rendezvousconnection = this.ensureRendezvousAsync(timeout);

            ListenerCommand listenerCommand = new ListenerCommand(null);
            listenerCommand.setResponse(responseCommand);
            String command = JSONObject.valueToString(listenerCommand);

            // We need to respond over the rendezvous connection
            rendezvousconnection.get();
            this.rendezvousWebSocket.sendAsync(command, timeout);

            if (responseCommand.hasBody() && responseBodyStream != null) {
                this.rendezvousWebSocket.sendAsync(responseBodyStream.array(), timeout);
            }
        }
    }

    private CompletableFuture<Void> sendBytesOverRendezvousAsync(ByteBuffer buffer, Duration timeout) {
    	// TODO: trace
//        RelayEventSource.Log.HybridHttpConnectionSendBytes(this.TrackingContext, buffer.Count);
        return this.rendezvousWebSocket.sendAsync(buffer, timeout);
    }

    private CompletableFuture<Void> ensureRendezvousAsync(Duration timeout) {
        if (this.rendezvousWebSocket == null) {
        	// TODO: trace
//            RelayEventSource.Log.HybridHttpCreatingRendezvousConnection(this.TrackingContext);
            // TODO: proxy
//            clientWebSocket.Options.Proxy = this.listener.Proxy;
            this.rendezvousWebSocket = new ClientWebSocket("HybridHttpConnection ensure Rendezvous");
            return this.rendezvousWebSocket.connectAsync(this.rendezvousAddress, timeout);
        }
        return CompletableFuture.completedFuture(null);
    }

//    private CompletableFuture<Void> closeAsync() {
////        if (this.rendezvousWebSocket != null) {
////            using (var cts = new CancellationTokenSource(this.OperationTimeout))
////            {
////                RelayEventSource.Log.ObjectClosing(this);
////                await this.rendezvousWebSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "NormalClosure", cts.Token).ConfigureAwait(false);
////                RelayEventSource.Log.ObjectClosed(this);
////            }
////        }
//    }

    static ListenerCommand.ResponseCommand createResponseCommand(RelayedHttpListenerContext listenerContext) {
    	RelayedHttpListenerResponse response = listenerContext.getResponse();
    	ListenerCommand.ResponseCommand responseCommand = new ListenerCommand.ResponseCommand();
        responseCommand.setStatusCode((int) response.getStatusCode());
        responseCommand.setStatusDescription(response.getStatusDescription());
        responseCommand.setRequestId(listenerContext.getTrackingContext().getTrackingId());
        response.getHeaders().forEach((key, val) -> responseCommand.getResponseHeaders().put(key, val));
        
        return responseCommand;
    }

    final class ResponseStream extends OutputStream {
        private static final long WRITE_BUFFER_FLUSH_TIMEOUT = 2000;
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

                // At this point we have no choice but to rendezvous
                CompletableFuture<Void> connectRendezvous = this.connection.ensureRendezvousAsync(timeout);

                // Send the response command over the rendezvous connection
                connectRendezvous.thenRunAsync(() -> this.connection.sendResponseAsync(responseCommand, null, timeout));
                this.responseCommandSent = true;

                if (this.writeBufferStream != null && this.writeBufferStream.Length > 0)
                {
                    var writeBuffer = this.writeBufferStream.GetArraySegment();
                    await this.connection.SendBytesOverRendezvousAsync(writeBuffer, WebSocketMessageType.Binary, false, cancelToken).ConfigureAwait(false);

                    this.writeBufferStream.Dispose();
                    this.writeBufferStream = null;
                    this.CancelWriteBufferFlushTimer();
                }
            }
        }

        public CompletableFuture<Void> writeAsync(byte[] array, int offset, int count) {
        	// TODO: trace
//            RelayEventSource.Log.HybridHttpResponseStreamWrite(this.TrackingContext, count);
        	Duration timeout = Duration.ofMillis(this.writeTimeout);
        	this.asyncLock.lockAsync(timeout).get();
        	
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
                            int initialStreamSize = Math.min(count * 2, MAX_CONTROL_CONNECTION_BODY_SIZE);
                            this.writeBufferStream = ByteBuffer.allocate(initialStreamSize);
                            this.writeBufferFlushTimer = new Timer();
                            
                            this.writeBufferFlushTimer.schedule(new TimerTask() {	
								@Override
								public void run() {
									OnWriteBufferFlushTimer();
								}
							}, WRITE_BUFFER_FLUSH_TIMEOUT, Long.MAX_VALUE);		
                            		
                        }
                        this.writeBufferStream.write(array, offset, count);
                        return CompletableFuture.completedFuture(null);
                    }

                    flushReason = FlushReason.BUFFER_FULL;
                }

                // FlushCoreAsync will rendezvous, send the responseCommand, and any writeBufferStream bytes
                this.flushCoreAsync(flushReason, timeout).get();
    		}
    		
    		ByteArrayBuffer buffer = new ByteArrayBuffer(array, offset, count);
            return this.connection.sendBytesOverRendezvousAsync(buffer, timeout);
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

        public CompletableFuture<Void> CloseAsync() {
            if (this.closed) {
                return CompletableFuture.completedFuture(null);
            }

            try {
            	// TODO: trace
//                RelayEventSource.Log.ObjectClosing(this);
            	this.asyncLock.lockAsync(Duration.ofMillis(this.writeTimeout)).thenRunAsync(() -> {
            		
            		if (!this.responseCommandSent) {
                        ListenerCommand.ResponseCommand responseCommand = createResponseCommand(this.context);
                        if (this.writeBufferStream != null) {
                            responseCommand.setBody(true);
                            this.writeBufferStream.Position = 0;
                        }

                        // Don't force any rendezvous now
                        await this.connection.SendResponseAsync(responseCommand, this.writeBufferStream, cancelSource.Token).ConfigureAwait(false);
                        this.responseCommandSent = true;
                        this.CancelWriteBufferFlushTimer();
                    }
                    else
                    {
                        var buffer = new ArraySegment<byte>(Array.Empty<byte>(), 0, 0);
                        await this.connection.SendBytesOverRendezvousAsync(buffer, WebSocketMessageType.Binary, true, cancelSource.Token).ConfigureAwait(false);
                    }
            	});

                RelayEventSource.Log.ObjectClosed(this);
            }
            catch (Exception e) when (!Fx.IsFatal(e))
            {
                RelayEventSource.Log.ThrowingException(e, this);
                throw;
            }
            finally
            {
                await this.connection.CloseAsync().ConfigureAwait(false);
                this.closed = true;
            }
        }

        async void OnWriteBufferFlushTimer() {
            try
            {
                using (var cancelSource = new CancellationTokenSource(this.WriteTimeout))
                using (await this.asyncLock.LockAsync(cancelSource.Token).ConfigureAwait(false))
                {
                    await this.FlushCoreAsync(FlushReason.Timer, cancelSource.Token).ConfigureAwait(false);
                }
            }
            catch (Exception e) when (!Fx.IsFatal(e))
            {
                RelayEventSource.Log.HandledExceptionAsWarning(this, e);
            }
        }

        void CancelWriteBufferFlushTimer() {
            if (this.writeBufferFlushTimer != null) {
                this.writeBufferFlushTimer.Change(Timeout.Infinite, Timeout.Infinite);
                this.writeBufferFlushTimer.Dispose();
                this.writeBufferFlushTimer = null;
            }
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

