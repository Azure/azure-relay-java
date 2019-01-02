package com.microsoft.azure.relay;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.component.LifeCycle;

@ClientEndpoint(configurator = HybridConnectionEndpointConfigurator.class)
public class ClientWebSocket {
	private Session session;
	private final WebSocketContainer container = ContainerProvider.getWebSocketContainer();
	private int maxMessageBufferSize = RelayConstants.DEFAULT_CONNECTION_BUFFER_SIZE;
	private CloseReason closeReason;
	private InputQueue<MessageFragment> messageQueue;
	private InputQueue<String> controlMessageQueue;
	private CompletableFuture<Void> closeTask = new CompletableFuture<Void>();
	
	public CloseReason getCloseReason() {
		return this.closeReason;
	}
	public int getMaxMessageBufferSize() {
		return maxMessageBufferSize;
	}
	public void setMaxMessageBufferSize(int maxMessageBufferSize) {
		this.maxMessageBufferSize = maxMessageBufferSize;
		this.container.setDefaultMaxTextMessageBufferSize(this.maxMessageBufferSize);
	}
	
	/**
	 * Creates a websocket instance
	 */
	public ClientWebSocket() {
		this.controlMessageQueue = new InputQueue<String>();
		this.messageQueue = new InputQueue<MessageFragment>();
		this.closeReason = null;
	}
	
	/**
	 * Establish websocket connection between the control websocket and the cloud service if not already established.
	 * @param uri The uri of the endpoint that the websocket is trying to connect to
	 * @return Returns a completableFuture which completes when websocket connection is established with the remote endpoint
	 */
	public CompletableFuture<Void> connectAsync(URI uri) {
		CompletableFuture<Void> future = new CompletableFuture<Void>();
		try {
			future = this.connectAsync(uri, null);
		} catch (CompletionException e) {
			e.printStackTrace();
		}
		return future;
	}
	
	/**
	 * Establish websocket connection between the control websocket and the cloud service if not already established.
	 * @param uri The uri of the endpoint that the websocket is trying to connect to
	 * @param timeout The timeout to connect to cloud service within
	 * @return Returns a completableFuture which completes when websocket connection is established with the remote endpoint
	 * @throws CompletionException Throws when connection could not be established within the given timeout
	 */
	public CompletableFuture<Void> connectAsync(URI uri, Duration timeout) throws CompletionException {
		if (this.isOpen()) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFutureUtil.timedRunAsync(timeout, () -> {
			try {
				this.container.setDefaultMaxTextMessageBufferSize(this.maxMessageBufferSize);
				this.session = this.container.connectToServer(this, uri);
				this.closeTask = new CompletableFuture<Void>();
			} catch (DeploymentException | IOException e) {
				throw new RuntimeIOException("connection to the server failed.");
			}
			if (this.session == null || !this.session.isOpen()) {
				throw new RuntimeIOException("connection to the server failed.");
			}
		});
	}
	
	/**
	 * Checks if this websocket is connected with its remote endpoint
	 * @return Boolean indicating if this websocket is connected with its remote endpoint
	 */
	public boolean isOpen() {
		return ((LifeCycle)this.container).isRunning() && this.session != null && this.session.isOpen();
	}
	
	/**
	 * Receives control messages
	 * @return Returns a CompletableFuture which completes when websocket receives control message
	 */
	public CompletableFuture<String> receiveControlMessageAsync() {
		return this.controlMessageQueue.dequeueAsync();
	}
	
	/**
	 * Receives byte messages from the remote sender. Blocks the thread until a whole message is received
	 * @return Returns a CompletableFuture of the bytes which completes when websocket receives the entire bytes
	 */
	public CompletableFuture<ByteBuffer> receiveMessageAsync() {
		AtomicBoolean receivedWholeMsg = new AtomicBoolean(true);
		LinkedList<byte[]> fragments = new LinkedList<byte[]>();
		AtomicInteger messageSize = new AtomicInteger(0);
		
		return CompletableFuture.supplyAsync(() -> {
			do {
				MessageFragment fragment = messageQueue.dequeueAsync().join();

				messageSize.set(messageSize.get() + fragment.getBytes().length);
				fragments.add(fragment.getBytes());
				receivedWholeMsg.set(fragment.hasEnded());
			}
			while (!receivedWholeMsg.get());
			
			byte[] message = new byte[messageSize.get()];
			int offset = 0;
			for (byte[] bytes : fragments) {
				System.arraycopy(bytes, 0, message, offset, bytes.length);
				offset += bytes.length;
			}
			
			return ByteBuffer.wrap(message);
		});
	}

	/**
	 * Sends the data to the remote endpoint. String or bytes will be sent as byte[], other objects will be sent as encoded object
	 * @param data Message to be sent
	 * @return Returns a CompletableFuture which completes when websocket finishes sending the bytes
	 */
	public CompletableFuture<Void> sendAsync(Object data) {
		CompletableFuture<Void> future = new CompletableFuture<Void>();
		try {
			future = this.sendAsync(data, null);
		} catch (CompletionException e) {
			// should not be an exception here because timeout is null
			e.printStackTrace();
		}
		return future;
	}
	
	/**
	 * Sends the data to the remote endpoint. String or bytes will be sent as byte[], other objects will be sent as encoded object
	 * @param data Message to be sent
	 * @param timeout The timeout to connect to send the data within
	 * @return Returns a CompletableFuture which completes when websocket finishes sending the bytes
	 * @throws CompletionException Throws when the sending task does not complete within the given timeout
	 */
	public CompletableFuture<Void> sendAsync(Object data, Duration timeout) throws CompletionException {
		if (this.session != null && this.session.isOpen()) {
			RemoteEndpoint.Async remote = this.session.getAsyncRemote();
			
			if (data == null) {
				return CompletableFuture.completedFuture(null);
			} 
			else if (data instanceof String) {
				String text = (String) data;
				return CompletableFutureUtil.timedRunAsync(timeout, () -> remote.sendBinary(ByteBuffer.wrap(text.getBytes())));
			} 
			else if (data instanceof byte[]) {
				return CompletableFutureUtil.timedRunAsync(timeout, () -> remote.sendBinary(ByteBuffer.wrap((byte[]) data)));
			}
			else if (data instanceof ByteBuffer) {
				return CompletableFutureUtil.timedRunAsync(timeout, () -> remote.sendBinary((ByteBuffer) data));
			}
			else {
				return CompletableFutureUtil.timedRunAsync(timeout, () -> remote.sendObject(data));
			}
		}
		else {
			throw new RuntimeIOException("cannot send because the session is not connected.");
		}
	}
	
	CompletableFuture<Void> sendCommandAsync(String command, Duration timeout) throws CompletionException {
		if (this.session == null) {
			throw new RuntimeIOException("cannot send because the session is not connected.");
		}
		RemoteEndpoint.Async remote = this.session.getAsyncRemote();
		return CompletableFutureUtil.timedRunAsync(timeout, () -> {
			remote.sendText(command);
		});
	}
	
	/**
	 * Closes the connection with the remote websocket
	 * @return Returns a CompletableFuture which completes when the connection is completely closed
	 */
	public CompletableFuture<Void> closeAsync() {
		return this.closeAsync(null);
	}
	
	public CompletableFuture<Void> closeAsync(CloseReason reason) {
		if (this.session == null || !this.session.isOpen()) {
			return this.closeTask;
		}
		try {
			if (reason != null) {
				this.session.close(reason);
			} else {
				this.session.close();
			}
		} catch (Exception e) {
			throw new RuntimeIOException("something went wrong when trying to close the websocket.");
		}
		return this.closeTask;
	}
	
    @OnOpen
    public void onWebSocketConnect(Session sess) {
        this.closeReason = null;
    }
    
    // Handles binary data sent to the listener
    @OnMessage
    public void onWebSocketBytes(byte[] inputBuffer, boolean isEnd) {
    	MessageFragment fragment = new MessageFragment(inputBuffer, isEnd);
        this.messageQueue.enqueueAndDispatch(fragment);
    }
    
    // Handles text from control message
    @OnMessage
    public void onWebSocketText(String text) {
    	this.controlMessageQueue.enqueueAndDispatch(text);
    }
    
    @OnClose
    public void onWebSocketClose(CloseReason reason) {
    	this.closeReason = reason;
    	this.messageQueue.shutdown();
    	this.closeTask.complete(null);
    	CompletableFutureUtil.cleanup();
    	try {
			((LifeCycle) this.container).stop();
		} catch (Exception e) { }
    	if (reason.getCloseCode() != CloseReason.CloseCodes.NORMAL_CLOSURE) {
    		throw new RuntimeIOException("did not close properly");
    	}
    }
    
    @OnError
    public void onWebSocketError(Throwable cause) {
    	// TODO: error handling if necessary
    }
    
    class MessageFragment {
    	private byte[] bytes;
    	private boolean ended;
    	
    	public MessageFragment(byte[] bytes, boolean ended) {
			this.bytes = bytes;
			this.ended = ended;
		}
    	
    	public byte[] getBytes() {
			return bytes;
		}

		public boolean hasEnded() {
    		return this.ended;
    	}
    }
}

