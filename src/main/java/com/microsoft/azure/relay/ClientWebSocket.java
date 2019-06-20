package com.microsoft.azure.relay;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.io.IOException;

import javax.websocket.*;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.UpgradeException;

class ClientWebSocket extends Endpoint implements RelayTraceSource {
	private final AutoShutdownScheduledExecutor executor;
	private final WebSocketContainer container = ContainerProvider.getWebSocketContainer();
	private final TrackingContext trackingContext;
	private Session session;
	private int maxMessageBufferSize = RelayConstants.DEFAULT_CONNECTION_BUFFER_SIZE;
	private CloseReason closeReason;
	private InputQueue<MessageFragment> fragmentQueue;
	private InputQueue<String> textQueue;
	private CompletableFuture<Void> closeTask;
	private String cachedString;

	/**
	 * Creates a websocket instance
	 */
	public ClientWebSocket(TrackingContext trackingContext, AutoShutdownScheduledExecutor executor) {
		this.executor = executor;
		this.textQueue = new InputQueue<String>(this.executor);
		this.fragmentQueue = new InputQueue<MessageFragment>(this.executor);
		this.closeReason = null;
		this.trackingContext = trackingContext;
	}
	
	public TrackingContext getTrackingContext() {
		return trackingContext;
	}
	
	@Override
	public String toString() {
		if (this.cachedString == null) {
			this.cachedString = this.getClass().getSimpleName() + "(" + this.trackingContext + ")";
		}
		return this.cachedString;
	}
	
	CloseReason getCloseReason() {
		return this.closeReason;
	}

	int getMaxMessageBufferSize() {
		return maxMessageBufferSize;
	}

	void setMaxMessageBufferSize(int maxMessageBufferSize) {
		if (maxMessageBufferSize > 0) {
			this.maxMessageBufferSize = maxMessageBufferSize;
			this.container.setDefaultMaxTextMessageBufferSize(this.maxMessageBufferSize);
		} else {
			throw new IllegalArgumentException("MaxBufferSize of the web socket must be a positive value.");
		}
	}
	
	/**
	 * Establish websocket connection between the control websocket and the cloud
	 * service if not already established.
	 * 
	 * @param uri The uri of the endpoint that the websocket is trying to connect to
	 * @return Returns a completableFuture which completes when websocket connection
	 *         is established with the remote endpoint
	 */
	public CompletableFuture<Void> connectAsync(URI uri) {
		return this.connectAsync(uri, null, null);
	}

	/**
	 * Establish websocket connection between the control websocket and the cloud
	 * service if not already established.
	 * 
	 * @param uri     The uri of the endpoint that the websocket is trying to
	 *                connect to
	 * @param timeout The timeout to connect to cloud service within
	 * @return Returns a completableFuture which completes when websocket connection
	 *         is established with the remote endpoint
	 * @throws CompletionException Throws when connection could not be established
	 *                             within the given timeout
	 */
	public CompletableFuture<Void> connectAsync(URI uri, Duration timeout) {
		return this.connectAsync(uri, timeout, null);
	}
	
	/**
	 * Establish websocket connection between the control websocket and the cloud
	 * service if not already established.
	 * 
	 * @param uri     The uri of the endpoint that the websocket is trying to
	 *                connect to
	 * @param timeout The timeout to connect to cloud service within
	 * @return Returns a completableFuture which completes when websocket connection
	 *         is established with the remote endpoint
	 * @throws CompletionException Throws when connection could not be established
	 *                             within the given timeout
	 */
	public CompletableFuture<Void> connectAsync(URI uri, Duration timeout, ClientEndpointConfig config) {
		if (this.isOpen()) {
			return CompletableFutureUtil.fromException(new RuntimeIOException("This connection is already connected."));
		}
		this.container.setDefaultMaxTextMessageBufferSize(this.maxMessageBufferSize);

		return CompletableFutureUtil.timedRunAsync(timeout, () -> {
			RelayLogger.logEvent("connecting", this);
			try {
				if (config != null) {
					this.container.connectToServer(this, config, uri);
				} else {
					this.container.connectToServer(this, uri);
				}
			} catch (DeploymentException | IOException e) {
				if (e.getCause() instanceof UpgradeException) {
					throw RelayLogger.throwingException(e.getCause(), this);
				}
				throw RelayLogger.throwingException(e, this);
			}
			
			if (this.session == null || !this.session.isOpen()) {
				throw RelayLogger.throwingException(new RuntimeIOException("connection to the server failed."), this);
			}
		},
		this.executor);
	}

	/**
	 * Checks if this websocket is connected with its remote endpoint
	 * 
	 * @return Boolean indicating if this websocket is connected with its remote
	 *         endpoint
	 */
	boolean isOpen() {
		return this.session != null && this.session.isOpen();
	}

	/**
	 * Receives text messages asynchronously.
	 * 
	 * @return Returns a CompletableFuture which completes when websocket receives text messages
	 */
	CompletableFuture<String> readTextAsync() {
		return this.textQueue.dequeueAsync().thenApply(text -> {
			if (text != null) {
				RelayLogger.logEvent("receivedText", this, String.valueOf(text.length()));
			}
			return text;
		});
	}
	
	/**
	 * Receives byte messages from the remote sender asynchronously.
	 * 
	 * @return Returns a CompletableFuture of the bytes which completes when websocket receives an entire message
	 */
	public CompletableFuture<ByteBuffer> readBinaryAsync() {
		return this.readBinaryAsync(null);
	}
	
	/**
	 * Receives byte messages from the remote sender asynchronously.
	 * 
	 * @param timeout The timeout duration for this operation.
	 * @return Returns a CompletableFuture of the bytes which completes when websocket receives the entire message.
	 * @throws TimeoutException thrown when a complete message frame is not received within the timeout.
	 */
	public CompletableFuture<ByteBuffer> readBinaryAsync(Duration timeout) {
		// Gather all fragments and return a single buffer
		BinaryMessageReader messageReader = new BinaryMessageReader(timeout);
		return messageReader.readAsync();
	}
	
	/**
	 * Sends the data to the remote endpoint as binary.
	 * 
	 * @param data Message to be sent.
	 * @return A CompletableFuture which completes when websocket finishes sending the bytes.
	 */
	public CompletableFuture<Void> writeAsync(Object data) {
		return this.writeAsync(data, null);
	}

	/**
	 * Sends the data to the remote endpoint within a timeout as binary.
	 * 
	 * @param data Message to be sent.
	 * @param timeout The timeout to connect to send the data within. May be null to indicate no timeout limit.
	 * @return A CompletableFuture which completes when websocket finishes sending the bytes.
	 * @throws TimeoutException Throws when the sending task does not complete within the given timeout.
	 */
	public CompletableFuture<Void> writeAsync(Object data, Duration timeout) {
		return writeAsync(data, timeout, true, WriteMode.BINARY);
	}
	
	/**
	 * Sends the data to the remote endpoint within a timeout in one of the WriteModes.
	 * 
	 * @param data Message to be sent.
	 * @param timeout The timeout to connect to send the data within. May be null to indicate no timeout limit.
	 * @param isEnd Indicates if the data sent is the end of a message
	 * @param mode The type of the message to be sent.
	 * @return A CompletableFuture which completes when websocket finishes sending the bytes.
	 * @throws TimeoutException Throws when the sending task does not complete within the given timeout.
	 */
	CompletableFuture<Void> writeAsync(Object data, Duration timeout, boolean isEnd, WriteMode mode) {
		if (this.isOpen()) {
			if (data == null) {
				// TODO: Log warns sending nothing because message is null
				return CompletableFuture.completedFuture(null);
			}
			else {
				RemoteEndpoint.Basic remote = this.session.getBasicRemote();
				RelayLogger.logEvent("writingBytes", this, mode.toString());
				
				// The websocket API will throw if multiple sends are attempted on the same websocket simultaneously
				return CompletableFutureUtil.timedRunAsync(timeout, () -> {
					try {
						if (mode.equals(WriteMode.TEXT)) {
							String text = data.toString();
							remote.sendText(text, isEnd);
							RelayLogger.logEvent("writingBytesFinished", this, String.valueOf(text.length()));
						}
						else {
							ByteBuffer bytes = null;
							if (data instanceof byte[]) {
								bytes = ByteBuffer.wrap(((byte[]) data).clone());
							} 
							else if (data instanceof ByteBuffer) {
								bytes = deepCopyByteBuffer((ByteBuffer) data);
							}
							else {
								throw new IllegalArgumentException(
									"The data to be sent should be ByteBuffer or byte[], but received " + data.getClass().getSimpleName());
							}
							
							int bytesToSend = bytes.remaining();
							// sendBinary() will cause the content of the byte array within the ByteBuffer to change
							remote.sendBinary(bytes, isEnd);
							RelayLogger.logEvent("writingBytesFinished", this, String.valueOf(bytesToSend));
						}
					} catch (Exception e) {
						throw RelayLogger.throwingException(e, this);
					}
				}, executor);
			}
		}
		else {
			return CompletableFutureUtil.fromException(new RuntimeIOException("cannot send because the session is not connected."));	
		}
	}
	
	/**
	 * Closes the connection with the remote websocket
	 * 
	 * @return Returns a CompletableFuture which completes when the connection is
	 *         completely closed
	 */
	public CompletableFuture<Void> closeAsync() {
		return this.closeAsync(null);
	}

	/**
	 * Closes the connection with the remote websocket with a given CloseReason
	 * 
	 * @param reason The CloseReason to be given for this operation. For details please see javax.websocket.CloseReason.
	 * @return Returns a CompletableFuture which completes when the connection is completely closed.
	 */
	public CompletableFuture<Void> closeAsync(CloseReason reason) {
		RelayLogger.logEvent("clientWebSocketClosing", this, (reason != null) ? reason.getReasonPhrase() : "NONE");
		
		if (this.session == null || !this.session.isOpen()) {
			return this.closeTask;
		}

		try {
			if (reason != null) {
				this.session.close(reason);
			} else {
				this.session.close();
			}
		} catch (Throwable e) {
			this.closeTask.completeExceptionally(e);
		}

		return this.closeTask;
	}
	
	@OnOpen
	public void onOpen(Session session, EndpointConfig config) {
		RelayLogger.logEvent("connected", this);
		this.closeReason = null;
		this.session = session;
		session.setMaxBinaryMessageBufferSize(this.maxMessageBufferSize);
		session.setMaxTextMessageBufferSize(this.maxMessageBufferSize);
		this.closeTask = new CompletableFuture<Void>();
		
		session.addMessageHandler(new MessageHandler.Whole<String>() {
			@Override
			public void onMessage(String text) {
				textQueue.enqueueAndDispatch(text);
			}
		});

		session.addMessageHandler(new MessageHandler.Partial<byte[]>() {
			@Override
			public void onMessage(byte[] inputBytes, boolean isEnd) {
				fragmentQueue.enqueueAndDispatch(new MessageFragment(inputBytes, isEnd));
			}
		});
	}
	
	@OnClose
	public void onClose(Session session, CloseReason reason) {
		CompletableFuture.runAsync(() -> {
			try {
				((LifeCycle) this.container).stop();	
			} catch (Exception e) {
				RelayLogger.handledExceptionAsWarning(e, this);
			}
		}, executor);
		
		this.closeReason = reason;
		RelayLogger.logEvent("clientWebSocketClosed", this, reason.getReasonPhrase());
		this.textQueue.shutdown();
		this.fragmentQueue.shutdown();
		this.closeTask.complete(null);
	}

	@OnError
	public void onError(Session session, Throwable cause) {
		RelayLogger.throwingException(cause, this);
	}
	
	private static ByteBuffer deepCopyByteBuffer(ByteBuffer original) {
       ByteBuffer clone = ByteBuffer.allocate(original.capacity());
       original.rewind();
       clone.put(original);
       original.rewind();
       clone.flip();
       return clone;
	}
	
	private static class MessageFragment {
		private final byte[] bytes;
		private final boolean ended;

		MessageFragment(byte[] bytes, boolean ended) {
			this.bytes = bytes;
			this.ended = ended;
		}

		byte[] getBytes() {
			return bytes;
		}

		boolean isEnd() {
			return this.ended;
		}
	}
	
	private final class BinaryMessageReader {
		private final TimeoutHelper timeoutHelper;
		private final LinkedList<byte[]> fragments;
		private int messageSize;
		
		BinaryMessageReader(Duration timeout) {
			timeoutHelper = new TimeoutHelper(timeout);
			fragments = new LinkedList<byte[]>();
		}
		
		public CompletableFuture<ByteBuffer> readAsync() {
			return readFragmentsAsync()
				.thenApply((voidResult) -> {
					byte[] message = new byte[messageSize];
					int offset = 0;
					for (byte[] bytes : fragments) {
						System.arraycopy(bytes, 0, message, offset, bytes.length);
						offset += bytes.length;
					}
					
					RelayLogger.logEvent("receivedBytes", this, Integer.toString(message.length));
					return ByteBuffer.wrap(message);
				});
		}
		
		private CompletableFuture<Void> readFragmentsAsync() {
			return fragmentQueue.dequeueAsync(timeoutHelper.remainingTime())
				.thenCompose((fragment) -> {
					if (fragment == null) {
						// TODO: In the case of shutdown should we throw if we don't make it to the end 
						// of message? We can't just give the user partial data without telling them.
						return CompletableFuture.completedFuture(null);
					}
	
					messageSize += fragment.getBytes().length;
					fragments.add(fragment.getBytes());
	
					if (!fragment.isEnd()) {
						return readFragmentsAsync();
					}
					
					return CompletableFuture.completedFuture(null);
				});
		}
	}
}
