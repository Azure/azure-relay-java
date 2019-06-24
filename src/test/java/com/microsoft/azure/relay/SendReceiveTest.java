package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;

import org.eclipse.jetty.http.HttpStatus;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.microsoft.azure.relay.HybridHttpConnection.ResponseStream;

public class SendReceiveTest {
	private static final int EMPTY_BYTES_SIZE = 0;
	private static final int SMALL_BYTES_SIZE = 256;
	private static final int LARGE_BYTES_SIZE = 64*1024 + 1;
	private static final int STATUS_CODE = HttpStatus.ACCEPTED_202;
	private static final String STATUS_DESCRIPTION = "OK";
	private static final byte[] EMPTY_BYTES = getTestBytes(EMPTY_BYTES_SIZE);
	private static final byte[] SMALL_BYTES = getTestBytes(SMALL_BYTES_SIZE);
	private static final byte[] LARGE_BYTES = getTestBytes(LARGE_BYTES_SIZE);
	
	private static HybridConnectionListener listener;
	private static TokenProvider tokenProvider;
	private static HybridConnectionClient client;
	private static int listenerReceiveCount = 0;
	private static int senderReceiveCount = 0;
	
	@BeforeClass
	public static void init() throws URISyntaxException {
		tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(TestUtil.KEY_NAME, TestUtil.KEY);
		listener = new HybridConnectionListener(new URI(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH), tokenProvider);
		listener.openAsync(Duration.ofSeconds(15)).join();
	}
	
	@AfterClass
	public static void cleanup() {
		listener.closeAsync().join();
	}
	
	@Before
	public void createClient() throws URISyntaxException {
		client = new HybridConnectionClient(new URI(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH), tokenProvider);
		senderReceiveCount = 0;
		listenerReceiveCount = 0;
	}
	
	@Test
	public void websocketSmallSendSmallResponseTest() {
		CompletableFuture<Void> listenerTask = sendAndReceiveWithWebsocketListener(SMALL_BYTES, SMALL_BYTES);
		CompletableFuture<Void> clientTask = sendAndReceiveWithWebsocketClient(SMALL_BYTES, SMALL_BYTES);
		listenerTask.join();
		clientTask.join();
	}

	@Test
	public void websocketSmallSendSmallResponseByteBufferTest() {
		CompletableFuture<Void> listenerTask = sendAndReceiveWithWebsocketListener(
			ByteBuffer.wrap(SMALL_BYTES.clone(), 10, SMALL_BYTES_SIZE / 2),
			ByteBuffer.wrap(SMALL_BYTES.clone(), 2, SMALL_BYTES_SIZE - 20));
		CompletableFuture<Void> clientTask = sendAndReceiveWithWebsocketClient(
			ByteBuffer.wrap(SMALL_BYTES.clone(), 2, SMALL_BYTES_SIZE - 20),
			ByteBuffer.wrap(SMALL_BYTES.clone(), 10, SMALL_BYTES_SIZE / 2));
		listenerTask.join();
		clientTask.join();
	}
	
	@Test
	public void websocketSmallSendLargeResponseTest() {
		CompletableFuture<Void> listenerTask = sendAndReceiveWithWebsocketListener(SMALL_BYTES, LARGE_BYTES);
		CompletableFuture<Void> clientTask = sendAndReceiveWithWebsocketClient(LARGE_BYTES, SMALL_BYTES);
		listenerTask.join();
		clientTask.join();
	}
	
	@Test
	public void websocketLargeSendSmallResponseTest() {
		CompletableFuture<Void> listenerTask = sendAndReceiveWithWebsocketListener(LARGE_BYTES, SMALL_BYTES);
		CompletableFuture<Void> clientTask = sendAndReceiveWithWebsocketClient(SMALL_BYTES, LARGE_BYTES);
		listenerTask.join();
		clientTask.join();
	}
	
	@Test
	public void websocketLargeSendLargeResponseTest() {
		CompletableFuture<Void> listenerTask = sendAndReceiveWithWebsocketListener(LARGE_BYTES, LARGE_BYTES);
		CompletableFuture<Void> clientTask = sendAndReceiveWithWebsocketClient(LARGE_BYTES, LARGE_BYTES);
		listenerTask.join();
		clientTask.join();
	}
	
	@Test
	public void websocketRepeatedSendReceiveTest() {
		int timesToRepeat = 3;
		CompletableFuture<Integer> senderReceiveCount = new CompletableFuture<Integer>();
		CompletableFuture<Integer> listenerReceiveCount = new CompletableFuture<Integer>();
		
		listener.acceptConnectionAsync().thenAcceptAsync(websocket -> {
			for (int i = 1; i <= timesToRepeat; i++) {
				websocket.readAsync().join();
				websocket.writeAsync(ByteBuffer.wrap(SMALL_BYTES)).join();
				if (i == timesToRepeat) {
					listenerReceiveCount.complete(i);
				}
			}
		});
		
		client.createConnectionAsync().thenAccept(clientWebSocket -> {
			for (int i = 1; i <= timesToRepeat; i++) {
				clientWebSocket.writeAsync(ByteBuffer.wrap(SMALL_BYTES)).join();
				clientWebSocket.readAsync().join();
				if (i == timesToRepeat) {
					senderReceiveCount.complete(i);
				}
			}
			clientWebSocket.closeAsync().join();
		});
		
		try {
			assertEquals("Listener did not receive expected number of messages.", timesToRepeat, listenerReceiveCount.get(timesToRepeat * 5, TimeUnit.SECONDS).intValue());
			assertEquals("Sender did not receive expected number of messages", timesToRepeat, senderReceiveCount.get(timesToRepeat * 5, TimeUnit.SECONDS).intValue());
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			fail("Repeated send and receives under websocket mode did not completely correctly in given amount of time");
		}
	}
	
	@Test
	public void websocketMultipleSenderTest() throws URISyntaxException {
		int numberOfSenders = 3;
		CompletableFuture<Void> senderReceiveTask = new CompletableFuture<Void>();
		
		for (int i = 1; i <= numberOfSenders; i++) {
			listener.acceptConnectionAsync().thenAccept(websocket -> {
				websocket.readAsync().thenRun(() -> {
					listenerReceiveCount++;
					websocket.writeAsync(ByteBuffer.wrap(SMALL_BYTES)).join();
					websocket.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "Normal closure from listener")).join();
				});
			});
			
			HybridConnectionClient newClient = new HybridConnectionClient(new URI(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH), tokenProvider);
			newClient.createConnectionAsync().thenAccept(socket -> {
				socket.writeAsync(ByteBuffer.wrap(SMALL_BYTES)).join();
				socket.readAsync().thenRun(() -> {
					senderReceiveCount++;
					if (senderReceiveCount == numberOfSenders) {
						senderReceiveTask.complete(null);
					}
				});
			}).join();
		}
		
		try {
			senderReceiveTask.get(numberOfSenders * 5, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			fail("Send and receive from multiple senders did not completely correctly in given amount of time");
		}
		assertEquals("Listener did not receive expected number of messages.", numberOfSenders, listenerReceiveCount);
		assertEquals("Sendner did not receive expected number of messages.", numberOfSenders, senderReceiveCount);
	}
	
	@Test
	public void httpGETAndSmallResponseTest() throws IOException {
		listener.setRequestHandler(context -> handleHttpRequest(context, EMPTY_BYTES, SMALL_BYTES));
        sendHttpRequest(SMALL_BYTES, EMPTY_BYTES);
	}
	
	@Test
	public void httpGETAndLargeResponseTest() throws IOException {
		listener.setRequestHandler(context -> handleHttpRequest(context, EMPTY_BYTES, LARGE_BYTES));
        sendHttpRequest(LARGE_BYTES, EMPTY_BYTES);
	}
	
	@Test
	public void httpSmallPOSTAndSmallResponseTest() throws IOException {
		listener.setRequestHandler(context -> handleHttpRequest(context, SMALL_BYTES, SMALL_BYTES));
        sendHttpRequest(SMALL_BYTES, SMALL_BYTES);
	}
	
	@Test
	public void httpSmallPOSTAndLargeResponseTest() throws IOException {
		listener.setRequestHandler(context -> handleHttpRequest(context, SMALL_BYTES, LARGE_BYTES));
        sendHttpRequest(LARGE_BYTES, SMALL_BYTES);
	}
	
	@Test
	public void httpLargePOSTAndSmallResponseTest() throws IOException {
		listener.setRequestHandler(context -> handleHttpRequest(context, LARGE_BYTES, SMALL_BYTES));
        sendHttpRequest(SMALL_BYTES, LARGE_BYTES);
	}
	
	@Test
	public void httpLargePOSTAndLargeResponseTest() throws IOException {
		listener.setRequestHandler(context -> handleHttpRequest(context, LARGE_BYTES, LARGE_BYTES));
        sendHttpRequest(LARGE_BYTES, LARGE_BYTES);
	}
	
	@Test
	public void httpWriteSmallThenSmallResponseTest() throws IOException {
		listener.setRequestHandler(context -> sendResponseMessages(context, SMALL_BYTES, SMALL_BYTES));
		sendHttpRequest(TestUtil.concatByteArrays(SMALL_BYTES, SMALL_BYTES), SMALL_BYTES);
	}
	
	@Test
	public void httpWriteSmallThenLargeResponseTest() throws IOException {
		listener.setRequestHandler(context -> sendResponseMessages(context, SMALL_BYTES, LARGE_BYTES));
		sendHttpRequest(TestUtil.concatByteArrays(SMALL_BYTES, LARGE_BYTES), SMALL_BYTES);
	}
	
	@Test
	public void httpWriteLargeThenSmallResponseTest() throws IOException {
		listener.setRequestHandler(context -> sendResponseMessages(context, LARGE_BYTES, SMALL_BYTES));
		sendHttpRequest(TestUtil.concatByteArrays(LARGE_BYTES, SMALL_BYTES), SMALL_BYTES);
	}
	
	@Test
	public void httpWriteResponseAfterFlushTimerTest() throws IOException {
		listener.setRequestHandler(context -> sendResponseMessages(context, true, SMALL_BYTES, SMALL_BYTES));
		sendHttpRequest(TestUtil.concatByteArrays(SMALL_BYTES, SMALL_BYTES), SMALL_BYTES);
	}
	
	private static CompletableFuture<Void> sendAndReceiveWithWebsocketClient(byte[] msgExpected, byte[] msgToSend) {
		return sendAndReceiveWithWebsocketClient(ByteBuffer.wrap(msgExpected.clone()), ByteBuffer.wrap(msgToSend.clone()));
	}

	private static CompletableFuture<Void> sendAndReceiveWithWebsocketClient(ByteBuffer msgExpected, ByteBuffer msgToSend) {
		AtomicBoolean receivedReply = new AtomicBoolean(false);
		
		return client.createConnectionAsync().thenCompose(channel -> {
			return channel.writeAsync(msgToSend).thenCompose($void -> {
				return channel.readAsync();
			}).thenCompose(msgReceived -> {
				receivedReply.set(true);
				assertTrue("Websocket sender did not receive the expected reply.", msgExpected.equals(msgReceived));
				return channel.closeAsync();
			}).thenRun(() -> assertTrue("Did not receive message from websocket sender.", receivedReply.get()));
		});
	}
	
	private static CompletableFuture<Void> sendAndReceiveWithWebsocketListener(byte[] msgExpected, byte[] msgToSend) {
		return sendAndReceiveWithWebsocketListener(ByteBuffer.wrap(msgExpected.clone()), ByteBuffer.wrap(msgToSend.clone()));
	}

	private static CompletableFuture<Void> sendAndReceiveWithWebsocketListener(ByteBuffer msgExpected, ByteBuffer msgToSend) {
		return listener.acceptConnectionAsync().thenComposeAsync(channel -> {
			return channel.readAsync().thenAccept(msgReceived -> {
				assertTrue("Websocket listener did not receive the expected reply.", msgExpected.equals(msgReceived));
			})
			.thenCompose(nullResult -> {
				return channel.writeAsync(msgToSend);
			})
			.thenCompose(nullResult -> {
				return channel.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "Listener closing normally."));
			});
		});
	}
	
	private static void handleHttpRequest(RelayedHttpListenerContext context, byte[] msgExpected, byte[] msgToSend) {
		try {
	        byte[] receivedText = readBytesFromStream(context.getRequest().getInputStream());
	        assertTrue("Listener did not received the expected message from http connection.", Arrays.equals(msgExpected, receivedText));
		} catch (IOException e) {
			fail(e.getMessage());
		}
        sendResponseMessages(context, msgToSend);
	}
	
	private static void sendHttpRequest(byte[] msgExpected, byte[] msgToSend) throws IOException {
		HttpURLConnection connection = getHttpConnection();
		sendBytesOverHttp(connection, msgToSend, 0, msgToSend.length);
		
		byte[] received = readBytesFromStream(connection.getInputStream());
		assertEquals("Http connection sender did not receive the expected response code.", STATUS_CODE, connection.getResponseCode());
		assertEquals("Http connection sender did not receive the expected response description.", STATUS_DESCRIPTION, connection.getResponseMessage());
		assertEquals("Http connection sender did not receive the expected response message size.", 
				msgExpected.length, 
				received.length);
		assertTrue("Http connection sender did not receive the expected response message.", Arrays.equals(msgExpected, received));
	}
	
	private static HttpURLConnection getHttpConnection() throws IOException {
		StringBuilder urlBuilder = new StringBuilder(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH);
		urlBuilder.replace(0, 5, "https://");
		URL url = new URL(urlBuilder.toString());
		String tokenString = tokenProvider.getTokenAsync(url.toString(), Duration.ofHours(1)).join().getToken();
		
		HttpURLConnection connection = (HttpURLConnection)url.openConnection();
		connection.setRequestProperty("ServiceBusAuthorization", tokenString);
		return connection;
	}
	
	private static void sendBytesOverHttp(HttpURLConnection connection, byte[] b, int off, int len) throws IOException {
		connection.setRequestMethod(len > 0 ? "POST" : "GET");
		connection.setDoOutput(true);
		OutputStream out = connection.getOutputStream();
		out.write(b, off, len);
		out.flush();
		out.close();
	}
	
	private static void sendResponseMessages(RelayedHttpListenerContext context, byte[]... messages) {
		sendResponseMessages(context, false, messages);
	}
	
	private static void sendResponseMessages(RelayedHttpListenerContext context, boolean hasPause, byte[]... messages) {
		RelayedHttpListenerResponse response = context.getResponse();
		response.setStatusCode(STATUS_CODE);
		response.setStatusDescription(STATUS_DESCRIPTION);
		
		try {
			for (int i = 0; i < messages.length; i++) {
				response.getOutputStream().writeAsync(messages[i].clone(), 0, messages[i].length).join();
				
				// Pause for testing the flush timeout
				if (hasPause && i < messages.length - 1) {
					Thread.sleep(ResponseStream.WRITE_BUFFER_FLUSH_TIMEOUT_MILLIS);
				}
			}
		} catch (Exception e) {
			fail(e.getMessage());
		} finally {
		    response.close();
		}
	}
	
	private static byte[] readBytesFromStream(InputStream inputStream) throws IOException {
		if (inputStream == null) {
			return new byte[0];
		}
		
		ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		while (true) {
			int i = inputStream.read();
			if (i == -1) {
				break;
			} else {
				byteStream.write(i);
			}
		}
		return byteStream.toByteArray();
	}
	
	/** Creates and fills a byte[] of given size by repeating bytes 0 to 255 **/
	private static byte[] getTestBytes(int size) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i % 256);
		}
		return bytes;
	}
}
