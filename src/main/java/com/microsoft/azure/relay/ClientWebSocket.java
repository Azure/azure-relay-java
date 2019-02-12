package com.microsoft.azure.relay;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.io.IOException;
import java.io.Writer;

import javax.websocket.*;

import org.eclipse.jetty.io.RuntimeIOException;
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
	 * Creates a websocket instance
	 */
	ClientWebSocket(TrackingContext trackingContext, AutoShutdownScheduledExecutor executor) {
		this.executor = executor;
		this.textQueue = new InputQueue<String>(this.executor);
		this.fragmentQueue = new InputQueue<MessageFragment>(this.executor);
		this.closeReason = null;
		this.trackingContext = trackingContext;
	}
	
	/**
	 * Establish websocket connection between the control websocket and the cloud
	 * service if not already established.
	 * 
	 * @param uri The uri of the endpoint that the websocket is trying to connect to
	 * @return Returns a completableFuture which completes when websocket connection
	 *         is established with the remote endpoint
	 */
	CompletableFuture<Void> connectAsync(URI uri) {
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
	CompletableFuture<Void> connectAsync(URI uri, Duration timeout) {
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
	CompletableFuture<Void> connectAsync(URI uri, Duration timeout, ClientEndpointConfig config) {
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
			RelayLogger.logEvent("receivedText", this, String.valueOf(text.length()));
			return text;
		});
	}
	
	/**
	 * Receives byte messages from the remote sender asynchronously.
	 * 
	 * @return Returns a CompletableFuture of the bytes which completes when websocket receives an entire message
	 */
	CompletableFuture<ByteBuffer> readBinaryAsync() {
		return this.readBinaryAsync(null);
	}
	
	/**
	 * Receives byte messages from the remote sender asynchronously.
	 * 
	 * @param timeout The timeout duration for this operation.
	 * @return Returns a CompletableFuture of the bytes which completes when websocket receives the entire message.
	 * @throws TimeoutException thrown when a complete message frame is not received within the timeout.
	 */
	CompletableFuture<ByteBuffer> readBinaryAsync(Duration timeout) {
		
		return CompletableFutureUtil.timedSupplyAsync(timeout, () -> {
				LinkedList<byte[]> fragments = new LinkedList<byte[]>();
				boolean receivedWholeMsg;
				int messageSize = 0;
				
				do {
					MessageFragment fragment = fragmentQueue.dequeueAsync().join();
					if (fragment == null) {
						break;
					}				
					messageSize += fragment.getBytes().length;
					fragments.add(fragment.getBytes());
					receivedWholeMsg = fragment.isEnd();
				} 
				while (!receivedWholeMsg);
	
				byte[] message = new byte[messageSize];
				int offset = 0;
				for (byte[] bytes : fragments) {
					System.arraycopy(bytes, 0, message, offset, bytes.length);
					offset += bytes.length;
				}
	
				RelayLogger.logEvent("receivedBytes", this, String.valueOf(message.length));
				return ByteBuffer.wrap(message);
			},
			this.executor);
	}
	
	/**
	 * Sends the data to the remote endpoint as binary.
	 * 
	 * @param data Message to be sent.
	 * @return A CompletableFuture which completes when websocket finishes sending the bytes.
	 */
	CompletableFuture<Void> writeAsync(Object data) {
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
	CompletableFuture<Void> writeAsync(Object data, Duration timeout) {
		return writeAsync(data, timeout, WriteMode.BINARY);
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
	CompletableFuture<Void> writeAsync(Object data, Duration timeout, WriteMode mode) {
		if (this.isOpen()) {
			if (data == null) {
				// TODO: Log warns sending nothing because message is null
				return CompletableFuture.completedFuture(null);
			}
			else {
				return CompletableFutureUtil.timedRunAsync(timeout, () -> {
					RemoteEndpoint.Basic remote = this.session.getBasicRemote();
					
					try {
						if (mode.equals(WriteMode.TEXT)) {
							String text = data.toString();
							RelayLogger.logEvent("writingBytes", this, "text");
							Writer writer = remote.getSendWriter();
							writer.write(text);
							writer.close();
							RelayLogger.logEvent("doneWritingBytes", this, String.valueOf(text.length()));
						}
						else {
							ByteBuffer bytes = null;
							
							RelayLogger.logEvent("writingBytes", this, "binary");
							if (data instanceof byte[]) {
								bytes = ByteBuffer.wrap((byte[]) data);
							} 
							else if (data instanceof ByteBuffer) {
								bytes = (ByteBuffer) data;
							}
							else if (data instanceof String){
								bytes = ByteBuffer.wrap(data.toString().getBytes(StringUtil.UTF8));
							}
							int bytesToSend = bytes.remaining();
							remote.sendBinary(bytes);
							RelayLogger.logEvent("doneWritingBytes", this, String.valueOf(bytesToSend));
						}
					}
					catch (IOException e) {
						throw RelayLogger.throwingException(e, this);
					}
				},
				this.executor);
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
	CompletableFuture<Void> closeAsync() {
		return this.closeAsync(null);
	}

	/**
	 * Closes the connection with the remote websocket with a given CloseReason
	 * 
	 * @param reason The CloseReason to be given for this operation. For details please see javax.websocket.CloseReason.
	 * @return Returns a CompletableFuture which completes when the connection is completely closed.
	 */
	CompletableFuture<Void> closeAsync(CloseReason reason) {
		RelayLogger.logEvent("closing", this);
		this.fragmentQueue.shutdown();
		this.textQueue.shutdown();
		
		if (this.session == null || !this.session.isOpen()) {
			return this.closeTask;
		}
		try {
			if (reason != null) {
				this.session.close(reason);
			} else {
				this.session.close();
			}
		} 
		catch (Throwable e) {
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
		RelayLogger.logEvent("closed", this);
		this.textQueue.shutdown();
		this.fragmentQueue.shutdown();
		
		this.closeReason = reason;
		if (reason.getCloseCode() != CloseReason.CloseCodes.NORMAL_CLOSURE) {
			this.closeTask.completeExceptionally(new RuntimeIOException("Websocket did not close properly: " + reason.toString()));
		} else {
			this.closeTask.complete(null);
		}
	}

	@OnError
	public void onError(Session session, Throwable cause) {
		RelayLogger.throwingException(cause, this);
	}
	
	class MessageFragment {
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
}

