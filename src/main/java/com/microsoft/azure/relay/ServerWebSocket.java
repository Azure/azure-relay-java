package com.microsoft.azure.relay;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.api.Session;

@ServerEndpoint(value="/{endpoint}")
public class ServerWebSocket
{
	private String endpoint;
	private Object dataIn;
	private Session session;
	private Object thisLock = new Object();
	
	public CompletableFuture<Object> receiveAsync() {
		synchronized (this.thisLock) {
			return CompletableFuture.completedFuture(this.dataIn);
		}
	}
	
	public Session getSession() {
		return this.session;
	}
	
    @OnOpen
    private void onWebSocketConnect(Session sess)
    {
        System.out.println("Socket Connected: " + sess);
    }
    
    @OnMessage
    private void onWebSocketText(String message) {
    	synchronized (thisLock) {
    		this.dataIn = message;
    		System.out.println("Received TEXT message: " + message);
    	}
    }
    
    @OnMessage
    private void onWebSocketData(byte[] bytes) {
    	
    	synchronized (thisLock) {
            System.out.println("Received BYTES message: " + bytes);
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            
            try {
	            ObjectInputStream is = new ObjectInputStream(in);
	            this.dataIn = is.readObject();
            } catch (IOException | ClassNotFoundException e) {
            	throw new RuntimeException("error when reading the inputs.");
            }
    	}
    }
    
    @OnClose
    private void onWebSocketClose(CloseReason reason) {
        System.out.println("Socket Closed: " + reason);
    }
    
    @OnError
    private void onWebSocketError(Throwable cause) {
        cause.printStackTrace(System.err);
    }
}
