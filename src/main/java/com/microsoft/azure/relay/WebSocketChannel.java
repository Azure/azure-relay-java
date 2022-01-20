// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import javax.websocket.CloseReason;

public class WebSocketChannel implements HybridConnectionChannel {
	private final ClientWebSocket websocket;
	private final TrackingContext trackingContext;
	
	WebSocketChannel(TrackingContext trackingContext, AutoShutdownScheduledExecutor executor) {
		this(new ClientWebSocket(trackingContext, executor), trackingContext);
	}
	
	WebSocketChannel(ClientWebSocket websocket, TrackingContext trackingContext) {
		this.websocket = websocket;
		this.trackingContext = trackingContext;
	}
	
	public TrackingContext getTrackingContext() {
		return this.trackingContext;
	}
	
	ClientWebSocket getWebSocket() {
		return this.websocket;
	}

	/**
	 * Checks whether the websocket connection is still open.
	 */
	@Override
	public boolean isOpen() {
		return this.websocket.isOpen();
	}
	
	/**
	 * Closes the websocket connection. Blocks until the connection is completely closed.
	 */
	@Override
	public void close() throws IOException {
		this.websocket.closeAsync().join();
	}
	
	/**
	 * Closes the connection with the remote websocket
	 * 
	 * @return Returns a CompletableFuture which completes when the connection is completely closed.
	 */
	public CompletableFuture<Void> closeAsync() {
		return this.websocket.closeAsync();
	}
	
	/**
	 * Closes the connection with the remote websocket with a given CloseReason
	 * 
	 * @param reason The CloseReason to be given for this operation. For details please see javax.websocket.CloseReason.
	 * @return Returns a CompletableFuture which completes when the connection is completely closed.
	 */
	public CompletableFuture<Void> closeAsync(CloseReason reason) {
		return this.websocket.closeAsync(reason);
	}

	/**
	 * Receives text messages asynchronously.
	 * 
	 * @return Returns a CompletableFuture which completes when websocket receives text messages.
	 */
	CompletableFuture<String> readTextAsync() {
		return this.websocket.readTextAsync();
	}

	/**
	 * Receives byte messages from the remote sender asynchronously.
	 * 
	 * @return Returns a CompletableFuture of the bytes which completes when websocket receives the entire message.
	 */
	public CompletableFuture<ByteBuffer> readAsync() {
		return this.websocket.readBinaryAsync();
	}
	
	/**
	 * Receives byte messages from the remote sender asynchronously within a given timeout.
	 * 
	 * @param timeout The timeout duration for this operation.
	 * @return Returns a CompletableFuture of the bytes which completes when websocket receives the entire message.
	 */
	public CompletableFuture<ByteBuffer> readAsync(Duration timeout) {
		return this.websocket.readBinaryAsync(timeout);
	}
	
	/**
	 * Sends the data to the remote endpoint as binary.
	 * 
	 * @param data Message to be sent.
	 * @return A CompletableFuture which completes when websocket finishes sending the bytes.
	 */
	public CompletableFuture<Void> writeAsync(ByteBuffer data) {
		return this.websocket.writeAsync(data);
	}

	/**
	 * Sends the data to the remote endpoint within a timeout as binary.
	 * 
	 * @param data Message to be sent.
	 * @param timeout The timeout to connect to send the data within. May be null to indicate no timeout limit.
	 * @return A CompletableFuture which completes when websocket finishes sending the bytes.
	 */
	public CompletableFuture<Void> writeAsync(ByteBuffer data, Duration timeout) {
		return this.websocket.writeAsync(data, timeout);
	}
	
	/**
	 * Sends the data to the remote endpoint within a timeout in one of the WriteModes.
	 * 
	 * @param data Message to be sent.
	 * @param timeout The timeout to connect to send the data within. May be null to indicate no timeout limit.
	 * @param mode The type of the message to be sent.
	 * @return A CompletableFuture which completes when websocket finishes sending the bytes.
	 * @throws TimeoutException Throws when the sending task does not complete within the given timeout.
	 */
	CompletableFuture<Void> writeAsync(Object data, Duration timeout, boolean isEnd, WriteMode mode) {
		return this.websocket.writeAsync(data, timeout, isEnd, mode);
	}
}
