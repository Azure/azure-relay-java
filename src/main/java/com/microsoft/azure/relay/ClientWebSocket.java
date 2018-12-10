package com.microsoft.azure.relay;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
	private Consumer<Session> onConnect;
	private Consumer<String> onMessage;
	private Consumer<CloseReason> onDisconnect;
	private final WebSocketContainer container = ContainerProvider.getWebSocketContainer();
	private Object thisLock = new Object();
	private int maxMessageBufferSize = RelayConstants.DEFAULT_CONNECTION_BUFFER_SIZE;
	private CloseReason closeReason;
	private InputQueue<MessageFragment> messageQueue;
	private InputQueue<String> controlMessageQueue;
	
	public Consumer<String> getOnMessage() {
		return onMessage;
	}
	public void setOnMessage(Consumer<String> onMessage) {
		this.onMessage = onMessage;
	}
	public Consumer<Session> getOnConnect() {
		return onConnect;
	}
	public void setOnConnect(Consumer<Session> onConnect) {
		this.onConnect = onConnect;
	}
	public Consumer<CloseReason> getOnDisconnect() {
		return onDisconnect;
	}
	public void setOnDisconnect(Consumer<CloseReason> onDisconnect) {
		this.onDisconnect = onDisconnect;
	}
	public Session getSession() {
		return this.session;
	}
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
	
	public ClientWebSocket() {
		this.controlMessageQueue = new InputQueue<String>();
		this.messageQueue = new InputQueue<MessageFragment>();
		this.closeReason = null;
	}
	
	public CompletableFuture<Void> connectAsync(URI uri) {
		return this.connectAsync(uri, null);
	}
	
	public CompletableFuture<Void> connectAsync(URI uri, Duration timeout) {
		return TimedCompletableFuture.timedRunAsync(timeout, () -> {
			try {
				this.container.setDefaultMaxTextMessageBufferSize(this.maxMessageBufferSize);
				this.session = this.container.connectToServer(this, uri);
			} catch (DeploymentException | IOException e) {
				throw new RuntimeException("connection to the server failed.");
			}
			if (this.session == null) {
				throw new RuntimeException("connection to the server failed.");
			}
		});
	}
	
	public boolean isOpen() {
		return ((LifeCycle)this.container).isRunning() && this.session != null && this.session.isOpen();
	}
	
	public CompletableFuture<String> receiveControlMessageAsync() {
		return this.controlMessageQueue.dequeueAsync();
	}
	
	// read the buffer data into the given buffer segment starting at offset and segment length of len, returns the number of bytes read
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

	public CompletableFuture<Void> sendAsync(Object data) {
		return this.sendAsync(data, null);
	}
	
	public CompletableFuture<Void> sendAsync(Object data, Duration timeout) {
		if (this.session != null && this.session.isOpen()) {
			RemoteEndpoint.Async remote = this.session.getAsyncRemote();
			
			if (data == null) {
				return CompletableFuture.completedFuture(null);
			} 
			else if (data instanceof String) {
				String text = (String) data;
				return TimedCompletableFuture.timedRunAsync(timeout, () -> remote.sendBinary(ByteBuffer.wrap(text.getBytes())));
			} 
			else if (data instanceof byte[]) {
				return TimedCompletableFuture.timedRunAsync(timeout, () -> remote.sendBinary(ByteBuffer.wrap((byte[]) data)));
			}
			else {
				return TimedCompletableFuture.timedRunAsync(timeout, () -> remote.sendObject(data));
			}
		}
		else {
			throw new RuntimeIOException("cannot send because the session is not connected.");
		}
	}
	
	protected CompletableFuture<Void> sendCommandAsync(String command, Duration timeout) {
		if (this.session == null) {
			throw new RuntimeIOException("cannot send because the session is not connected.");
		}
		RemoteEndpoint.Async remote = this.session.getAsyncRemote();
		return TimedCompletableFuture.timedRunAsync(timeout, () -> {
			remote.sendText(command);
		});
	}
	
	public void close(CloseReason reason) {
		if (this.session == null || !this.session.isOpen()) {
			return;
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
	}
	
    @OnOpen
    public void onWebSocketConnect(Session sess) {
        this.closeReason = null;
        if (this.onConnect != null) {
        	this.onConnect.accept(sess);
        }
    }
    
    // Handles binary data sent to the listener
    @OnMessage
    public void onWebSocketBytes(byte[] inputBuffer, boolean isEnd) {
    	MessageFragment fragment = new MessageFragment(inputBuffer, isEnd);
        this.messageQueue.enqueueAndDispatch(fragment);
    	
		if (isEnd && this.onMessage != null) {
			String msg = new String(inputBuffer);
			this.onMessage.accept(msg);
		}
    }
    
    // Handles text from control message
    @OnMessage
    public void onWebSocketText(String text) {
    	this.controlMessageQueue.enqueueAndDispatch(text);
    }
    
    @OnClose
    public void onWebSocketClose(CloseReason reason) {
    	try {
	    	System.out.println("Close reason: " + reason.getReasonPhrase());
	    	this.closeReason = reason;
	    	((LifeCycle) this.container).stop();
	    	if (this.closeReason.getCloseCode() != CloseReason.CloseCodes.NORMAL_CLOSURE) {
	    		throw new RuntimeIOException("did not close properly");
	    	}
    	}
    	catch (Exception e) {
    		System.out.println(e.getMessage());
    	}
    	if (this.onDisconnect != null) {
    		this.onDisconnect.accept(reason);
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

