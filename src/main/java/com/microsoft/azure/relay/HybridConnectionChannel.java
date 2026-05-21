// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.websocket.api.CloseStatus;

public interface HybridConnectionChannel extends Channel {
	
	public TrackingContext getTrackingContext();

	/**
	 * Closes the connection with the remote websocket
	 * 
	 * @return Returns a CompletableFuture which completes when the connection is completely closed.
	 */
	public CompletableFuture<Void> closeAsync();
	
	/**
	 * Closes the connection with the remote websocket with a given CloseStatus
	 * 
	 * @param closeStatus The CloseStatus to be given for this operation. For details please see org.eclipse.jetty.websocket.api.CloseStatus.
	 * @return Returns a CompletableFuture which completes when the connection is completely closed.
	 */
	public CompletableFuture<Void> closeAsync(CloseStatus closeStatus);
	
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
	 */
	public CompletableFuture<Void> writeAsync(ByteBuffer data, Duration timeout);
}
