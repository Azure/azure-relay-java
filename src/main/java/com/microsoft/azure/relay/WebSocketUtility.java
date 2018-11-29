package com.microsoft.azure.relay;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public class WebSocketUtility {
	public enum WebSocketMessageType { TEXT, BINARY, CLOSE };
	
//    public static CompletableFuture<Void> sendStreamAsync(ClientWebSocket webSocket, ByteBuffer stream, ByteBuffer buffer, Duration timeout) {
//        boolean endOfMessage = false;
//        int totalBytesSent = 0;
//        int bytesRead;
//        do {
//        	int oldPos = buffer.position();
//            buffer.get(stream.array(), 0, buffer.capacity());
//            int newPos = buffer.position();
//            bytesRead = newPos - oldPos;
//            
//            if (bytesRead == 0) {
//                // Send an empty frame with EndOfMessage = true
//                endOfMessage = true;
//                return webSocket.sendAsync(new byte[0], timeout);
//            }
//            else {
//                totalBytesSent += bytesRead;
//                endOfMessage = totalBytesSent == stream.position();
//                webSocket.sendAsync(Arrays.copyOfRange(buffer.array(), buffer.position(), bytesRead), timeout);
//            }
//        }
//        while (!endOfMessage);
//    }
//
//    /// <summary>
//    /// Reads fragments from a WebSocket until an entire message or close is received.
//    /// </summary>
//    public static CompletableFuture<WebSocketReadMessageResult> readMessageAsync(ClientWebSocket webSocket, ByteBuffer destinationStream, Duration Timeout) {
//    	WebSocketReadMessageResult readResult = new WebSocketReadMessageResult();
//    	do {
//            webSocket.receiveAsync(buffer)
//        }
//        while (!readFragmentResult.EndOfMessage && readFragmentResult.MessageType != WebSocketMessageType.Close);
//
//        readMessageResult.MessageType = readFragmentResult.MessageType;
//        readMessageResult.CloseStatus = readFragmentResult.CloseStatus;
//        readMessageResult.CloseStatusDescription = readFragmentResult.CloseStatusDescription;
//        return readMessageResult;
//    }
    

}

