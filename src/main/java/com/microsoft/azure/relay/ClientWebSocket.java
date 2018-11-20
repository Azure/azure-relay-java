package com.microsoft.azure.relay;

import java.net.URI;
import java.nio.CharBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.Reader;

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
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;

@ClientEndpoint(configurator = HybridConnectionEndpointConfigurator.class)
public class ClientWebSocket {
	private Session session;
	private Consumer<Session> onConnect;
	private Consumer<String> onMessage;
	private Consumer<CloseReason> onDisconnect;
	private static final WebSocketContainer container = ContainerProvider.getWebSocketContainer();
	private Object thisLock = new Object();
//	private StringBuffer textBuffer;
	private char[] charBuffer = new char[RelayConstants.DEFAULT_CONNECTION_BUFFER_SIZE];
	private final byte[] byteBuffer = new byte[RelayConstants.DEFAULT_CONNECTION_BUFFER_SIZE];
	private final String origin;
	
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
	
	public ClientWebSocket(String origin) {
		this.origin = origin;
	}
	
	public CompletableFuture<Void> connectAsync(URI uri) {
		return this.connectAsync(uri, null);
	}
	
	public CompletableFuture<Void> connectAsync(URI uri, Duration timeout) {
		return TimedCompletableFuture.timedRunAsync(timeout, () -> {
			try {
				container.setDefaultMaxTextMessageBufferSize(RelayConstants.DEFAULT_CONNECTION_BUFFER_SIZE);
				this.session = container.connectToServer(this, uri);
			} catch (DeploymentException | IOException e) {
				throw new RuntimeException("connection to the server failed.");
			}
			if (this.session == null) {
				throw new RuntimeException("connection to the server failed.");
			}
		});
	}
	
	// Used to read binary data
	public CompletableFuture<byte[]> receiveAsync(InputStream reader) {
		synchronized (this.thisLock) {
			try {
				CompletableFuture.runAsync(() -> {
					try {
						reader.read(this.byteBuffer);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}).get();
			} catch (InterruptedException | ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return CompletableFuture.supplyAsync(() -> this.byteBuffer);
		}
	}
	
	// Used to read text
//	public CompletableFuture<String> receiveAsync(Reader reader) {
//		synchronized (this.thisLock) {
//			try {
//				CompletableFuture.runAsync(() -> {
//					try {
//						reader.read(this.charBuffer);
//					} catch (IOException e) {
//						// TODO Auto-generated catch block
//						e.printStackTrace();
//					}
//				}).get();
//			} catch (InterruptedException | ExecutionException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//			return CompletableFuture.supplyAsync(() -> new String(this.charBuffer));
//		}
//	}
	
	public String receiveText() {
		String text = new String(this.charBuffer);
		return text;
	}

	public CompletableFuture<Void> sendAsync(Object data) {
		return this.sendAsync(data, null);
	}
	
	public CompletableFuture<Void> sendAsync(Object data, Duration timeout) {
		if (this.session != null) {
			RemoteEndpoint.Async remote = this.session.getAsyncRemote();
			
			if (data == null) {
				return CompletableFuture.completedFuture(null);
			} 
			else if (data instanceof String) {
				return CompletableFuture.runAsync(() -> remote.sendText((String) data));
//				return TimedCompletableFuture.timedRunAsync(timeout, () -> remote.sendText((String) data));
			} 
			else {
				return CompletableFuture.runAsync(() -> remote.sendObject(data));
//				return TimedCompletableFuture.timedRunAsync(timeout, () -> remote.sendObject(data));
			}
		}
		else {
			throw new RuntimeIOException("cannot send because the session is not connected.");
		}
	}
	
    @OnOpen
    public void onWebSocketConnect(Session sess) {
        System.out.println("Socket Connected: " + sess);
        if (this.onConnect != null) {
        	this.onConnect.accept(sess);
        }
    }
    
    // Handles binary data sent to the listener
    @OnMessage
    public void onWebSocketBytes(byte[] reader, boolean hasEnd) {
    	synchronized (this.thisLock) {
//    		CompletableFuture<byte[]> receivedBytes = receiveAsync(reader);
//        	if (isEnd && this.onMessage != null) {
//            	receivedBytes.thenAccept((text) -> {
//            		String msg = new String(text);
//            		this.onMessage.accept(msg);
//            	});
//        	}
    		if (hasEnd && this.onMessage != null) {
    			String msg = new String(reader);
    			this.onMessage.accept(msg);
    		}
    	}
    }
    
    // Handles text from control message
    @OnMessage
    public void onWebSocketText(String text, boolean hasEnded) {
    	synchronized (this.thisLock) {
    		this.charBuffer = text.toCharArray();
    		if (hasEnded && this.onMessage != null) {
    			this.onMessage.accept(this.receiveText());
    		}
    		
//    		CompletableFuture<String> receivedBytes = receiveAsync(reader);
//        	if (this.onMessage != null) {
//            	receivedBytes.thenAccept((text) -> {
//            		this.onMessage.accept(text);
//            	});
//        	}
    	}
    }
    
//    @OnMessage
//    public void onWebSocketData(byte[] bytes) {
//    	
//    	synchronized (this.thisLock) {
//            System.out.println("Received BYTES message: " + bytes);
//            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
//            
//            try {
//	            ObjectInputStream is = new ObjectInputStream(in);
//	            this.dataIn = is.readObject();
//	            System.out.println(this.dataIn);
//	            is.close();
//	            in.close();
//            } catch (IOException | ClassNotFoundException e) {
//            	System.out.println(e.getClass());
//            	throw new RuntimeException("error when reading the inputs.");
//            }
//    	}
//    }
    
    @OnClose
    public void onWebSocketClose(CloseReason reason) {
    	if (this.onDisconnect != null) {
    		this.onDisconnect.accept(reason);
    	}
    }
    
    @OnError
    public void onWebSocketError(Throwable cause) {
        cause.printStackTrace(System.err);
    }
}

