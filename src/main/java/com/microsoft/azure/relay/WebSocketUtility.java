package com.microsoft.azure.relay;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class WebSocketUtility {
	
    public static CompletableFuture<Void> sendStreamAsync(ClientWebSocket webSocket, ByteBuffer stream, ByteBuffer buffer, Duration timeout) {
        boolean endOfMessage = false;
        int totalBytesSent = 0;
        int bytesRead;
        do {
        	int oldPos = buffer.position();
            stream.get(buffer.array(), 0, buffer.position());
            int newPos = buffer.position();
            bytesRead = newPos - oldPos;
            
            if (stream.position() == 0) {
                // Send an empty frame with EndOfMessage = true
                endOfMessage = true;
                return webSocket.sendAsync(new byte[0], timeout);
            }
            else {
                totalBytesSent += bytesRead;
                endOfMessage = totalBytesSent == stream.position();
                webSocket.sendAsync(new ArraySegment<byte>(buffer.array(), buffer.position(), bytesRead), timeout);
            }
        }
        while (!endOfMessage);
    }

    /// <summary>
    /// Reads fragments from a WebSocket until an entire message or close is received.
    /// </summary>
    public static async Task<WebSocketReadMessageResult> ReadMessageAsync(WebSocket webSocket, ArraySegment<byte> buffer, Stream destinationStream, CancellationToken cancelToken)
    {
        var readMessageResult = new WebSocketReadMessageResult();
        WebSocketReceiveResult readFragmentResult;
        do
        {
            readFragmentResult = await webSocket.ReceiveAsync(buffer, cancelToken).ConfigureAwait(false);
            readMessageResult.Length += readFragmentResult.Count;
            destinationStream.Write(buffer.Array, buffer.Offset, readFragmentResult.Count);
        }
        while (!readFragmentResult.EndOfMessage && readFragmentResult.MessageType != WebSocketMessageType.Close);

        readMessageResult.MessageType = readFragmentResult.MessageType;
        readMessageResult.CloseStatus = readFragmentResult.CloseStatus;
        readMessageResult.CloseStatusDescription = readFragmentResult.CloseStatusDescription;
        return readMessageResult;
    }
}
