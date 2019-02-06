package com.microsoft.azure.relay;

import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import javax.websocket.CloseReason;

public interface HybridConnectionChannel extends Channel {
	
	public TrackingContext getTrackingContext();

	/**
	 * Closes the connection with the remote websocket
	 * 
	 * @return Returns a CompletableFuture which completes when the connection is completely closed.
	 */
	public CompletableFuture<Void> closeAsync();
	
	/**
	 * Closes the connection with the remote websocket with a given CloseReason
	 * 
	 * @param reason The CloseReason to be given for this operation. For details please see javax.websocket.CloseReason.
	 * @return Returns a CompletableFuture which completes when the connection is completely closed.
	 */
	public CompletableFuture<Void> closeAsync(CloseReason reason);
	
	/**
	 * Receives byte messages from the remote sender asynchronously.
	 * 
	 * @return Returns a CompletableFuture of the bytes which completes when websocket receives the entire message.
	 */
	public CompletableFuture<ByteBuffer> readAsync();
	
	/**
	 * Receives byte messages from the remote sender asynchronously within a given timeout.
	 * 
	 * @param timeout The timeout duration for this operation.
	 * @return Returns a CompletableFuture of the bytes which completes when websocket receives the entire message.
	 * @throws TimeoutException thrown when a complete message frame is not received within the timeout.
	 */
	public CompletableFuture<ByteBuffer> readAsync(Duration timeout);
	
	/**
	 * Sends the data to the remote endpoint as binary.
	 * 
	 * @param data Message to be sent.
	 * @return A CompletableFuture which completes when websocket finishes sending the bytes.
	 */
	public CompletableFuture<Void> writeAsync(ByteBuffer data);
	
	/**
	 * Sends the data to the remote endpoint within a timeout as binary.
	 * 
	 * @param data Message to be sent.
	 * @param timeout The timeout to connect to send the data within. May be null to indicate no timeout limit.
	 * @return A CompletableFuture which completes when websocket finishes sending the bytes.
	 * @throws TimeoutException Throws when the sending task does not complete within the given timeout.
	 */
	public CompletableFuture<Void> writeAsync(ByteBuffer data, Duration timeout);
}
