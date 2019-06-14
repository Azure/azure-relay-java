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
	private static HybridConnectionListener listener;
	private static TokenProvider tokenProvider;
	private static HybridConnectionClient client;
	private static final int STATUS_CODE = HttpStatus.ACCEPTED_202;
	private static final String STATUS_DESCRIPTION = "OK";
	private static int listenerReceiveCount = 0;
	private static int senderReceiveCount = 0;
	
	// empty test string for GET method
	private static String emptyStr = "";
	// small test string that's under 64kb
	private static String smallStr = "smallStr";
	// large test string that's over 64kb
	private static String largeStr;
	
	@BeforeClass
	public static void init() throws URISyntaxException {
		tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(TestUtil.KEY_NAME, TestUtil.KEY);
		listener = new HybridConnectionListener(new URI(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH), tokenProvider);

		// Build the large string in small chunks because hardcoding a large string may not compile
		StringBuilder builder = new StringBuilder();
		String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
		for (int i = 0; i < 2000; i++) {
			builder.append(alphabet);
		}
		largeStr = builder.toString();
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
		CompletableFuture<Void> listenerTask = websocketListener(smallStr, smallStr);
		CompletableFuture<Void> clientTask = websocketClient(smallStr, smallStr);
		listenerTask.join();
		clientTask.join();
	}
	
	@Test
	public void websocketSmallSendLargeResponseTest() {
		CompletableFuture<Void> listenerTask = websocketListener(smallStr, largeStr);
		CompletableFuture<Void> clientTask = websocketClient(largeStr, smallStr);
		listenerTask.join();
		clientTask.join();
	}
	
	@Test
	public void websocketLargeSendSmallResponseTest() {
		CompletableFuture<Void> listenerTask = websocketListener(largeStr, smallStr);
		CompletableFuture<Void> clientTask = websocketClient(smallStr, largeStr);
		listenerTask.join();
		clientTask.join();
	}
	
	@Test
	public void websocketLargeSendLargeResponseTest() {
		CompletableFuture<Void> listenerTask = websocketListener(largeStr, largeStr);
		CompletableFuture<Void> clientTask = websocketClient(largeStr, largeStr);
		listenerTask.join();
		clientTask.join();
	}
	
	@Test
	public void websocketRepeatedSendReceiveTest() {
		int timesToRepeat = 3;
		CompletableFuture<Integer> senderReceiveCount = new CompletableFuture<Integer>();
		CompletableFuture<Integer> listenerReceiveCount = new CompletableFuture<Integer>();
		
		listener.acceptConnectionAsync().thenAcceptAsync((websocket) -> {
			for (int i = 1; i <= timesToRepeat; i++) {
				websocket.readAsync().join();
				websocket.writeAsync(StringUtil.toBuffer("hi")).join();
				if (i == timesToRepeat) {
					listenerReceiveCount.complete(i);
				}
			}
		});
		
		client.createConnectionAsync().thenAccept((clientWebSocket) -> {
			for (int i = 1; i <= timesToRepeat; i++) {
				clientWebSocket.writeAsync(StringUtil.toBuffer("hi")).join();
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
			listener.acceptConnectionAsync().thenAccept((websocket) -> {
				websocket.readAsync().thenRun(() -> {
					listenerReceiveCount++;
					websocket.writeAsync(StringUtil.toBuffer("hi")).join();
					websocket.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "Normal closure from listener")).join();
				});
			});
			
			HybridConnectionClient newClient = new HybridConnectionClient(new URI(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH), tokenProvider);
			newClient.createConnectionAsync().thenAccept((socket) -> {
				socket.writeAsync(StringUtil.toBuffer("hi")).join();
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
	public void websocketWriteReceiveAllByteValuesTest() throws URISyntaxException {
		CompletableFuture<Void> listenerTask = listener.acceptConnectionAsync().thenCompose(connection -> {
			return connection.readAsync()
				.thenCompose(bufferReceived -> {
					byte[] byteSet = getByteSet();
					assertTrue("Websocket listener did not receive the expected bytes.", Arrays.equals(byteSet, bufferReceived.array()));
					return connection.writeAsync(ByteBuffer.wrap(byteSet));
				})
				.thenCompose($void -> {
					return connection.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "Normal closure from listener"));
				});
		});
		
		HybridConnectionClient newClient = new HybridConnectionClient(new URI(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH), tokenProvider);
		CompletableFuture<Void> clientTask = newClient.createConnectionAsync().thenCompose(connection -> {
			return connection.writeAsync(ByteBuffer.wrap(getByteSet()))
					.thenCompose($void -> {
						return connection.readAsync();
					})
					.thenAccept(bufferReceived -> {
						assertTrue("Websocket client did not receive the expected bytes.", Arrays.equals(getByteSet(), bufferReceived.array()));
					});
		});
		listenerTask.join();
		clientTask.join();
	}
	
	@Test
	public void httpGETAndSmallResponseTest() throws IOException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, emptyStr, smallStr));
        httpRequestSender("GET", smallStr, emptyStr);
	}
	
	@Test
	public void httpGETAndLargeResponseTest() throws IOException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, emptyStr, largeStr));
        httpRequestSender("GET", largeStr, emptyStr);
	}
	
	@Test
	public void httpSmallPOSTAndSmallResponseTest() throws IOException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, smallStr, smallStr));
        httpRequestSender("POST", smallStr, smallStr);
	}
	
	@Test
	public void httpSmallPOSTAndLargeResponseTest() throws IOException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, smallStr, largeStr));
        httpRequestSender("POST", largeStr, smallStr);
	}
	
	@Test
	public void httpLargePOSTAndSmallResponseTest() throws IOException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, largeStr, smallStr));
        httpRequestSender("POST", smallStr, largeStr);
	}
	
	@Test
	public void httpLargePOSTAndLargeResponseTest() throws IOException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, largeStr, largeStr));
        httpRequestSender("POST", largeStr, largeStr);
	}
	
	@Test
	public void httpWriteSmallThenSmallResponseTest() throws IOException {
		listener.setRequestHandler(context -> sendResponseMessages(context, new String[] {smallStr, smallStr}, false));
		httpRequestSender("POST", smallStr + smallStr, smallStr);
	}
	
	@Test
	public void httpWriteSmallThenLargeResponseTest() throws IOException {
		listener.setRequestHandler(context -> sendResponseMessages(context, new String[] {smallStr, largeStr}, false));
		httpRequestSender("POST", smallStr + largeStr, smallStr);
	}
	
	@Test
	public void httpWriteLargeThenSmallResponseTest() throws IOException {
		listener.setRequestHandler(context -> sendResponseMessages(context, new String[] {largeStr, smallStr}, false));
		httpRequestSender("POST", largeStr + smallStr, smallStr);
	}
	
	@Test
	public void httpWriteResponseAfterFlushTimerTest() throws IOException {
		listener.setRequestHandler(context -> sendResponseMessages(context, new String[] {smallStr, smallStr}, true));
		httpRequestSender("POST", smallStr + smallStr, smallStr);
	}
	
	@Test
	public void httpWriteResponseEnsureOrderTest() throws IOException {
		String[] messages = new String[1010]; // 1000 small messages followed by 10 large ones
		for (int i = 0; i < messages.length; i++) {
			messages[i] = i + ((i < 1000) ? smallStr : largeStr);
		}
		listener.setRequestHandler(context -> sendResponseMessages(context, messages, false));
		httpRequestSender("POST", String.join("", messages), smallStr);
	}
	
	@Test
	public void httpWriteReceiveAllByteValuesTest() throws IOException {
		byte[] byteSet = getByteSet();
		listener.setRequestHandler(context -> {
			RelayedHttpListenerResponse response = context.getResponse();
			response.setStatusCode(STATUS_CODE);
			response.setStatusDescription(STATUS_DESCRIPTION);
			
			try {
				response.getOutputStream().writeAsync(byteSet, 0, byteSet.length).join();
			} catch (Exception e) {
				fail(e.getMessage());
			} finally {
			    context.getResponse().close();
			}
		});
		
		HttpURLConnection connection = getHttpConnection();
		sendBytesOverHttp(connection, byteSet, 0, byteSet.length);
		byte[] received = readBytesFromStream(connection.getInputStream());
		assertEquals("Http connection sender did not receive the expected response code.", STATUS_CODE, connection.getResponseCode());
		assertEquals("Http connection sender did not receive the expected response description.", STATUS_DESCRIPTION, connection.getResponseMessage());
		assertEquals("Http connection sender did not receive the expected response message size.", byteSet.length, received.length);
		assertTrue("Http connection sender did not receive the expected response message.", Arrays.equals(byteSet, received));
	}
	
	private static CompletableFuture<Void> websocketClient(String msgExpected, String msgToSend) {
		AtomicBoolean receivedReply = new AtomicBoolean(false);
		
		return client.createConnectionAsync().thenCompose((channel) -> {
			return channel.writeAsync(StringUtil.toBuffer(msgToSend)).thenCompose($void -> {
				return channel.readAsync();
			}).thenCompose((bytesReceived) -> {
				String msgReceived = new String(bytesReceived.array());
				receivedReply.set(true);
				assertEquals("Websocket sender did not receive the expected reply.", msgExpected, msgReceived);
				return channel.closeAsync();
			}).thenRun(() -> assertTrue("Did not receive message from websocket sender.", receivedReply.get()));
		});
	}
	
	private static CompletableFuture<Void> websocketListener(String msgExpected, String msgToSend) {
		return listener.acceptConnectionAsync().thenComposeAsync((channel) -> {
			return channel.readAsync().thenAccept(bytesReceived -> {
				assertEquals("Websocket listener did not receive the expected message.", msgExpected, new String(bytesReceived.array()));
			})
			.thenCompose(nullResult -> {
				return channel.writeAsync(StringUtil.toBuffer(msgToSend));
			})
			.thenCompose(nullResult -> {
				return channel.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "Listener closing normally."));
			});
		});
	}
	
	private static void httpRequestHandler(RelayedHttpListenerContext context, String msgExpected, String msgToSend) {
		try {
	        String receivedText = new String(readBytesFromStream(context.getRequest().getInputStream()));
	        assertEquals("Listener did not received the expected message from http connection.", msgExpected, receivedText);
		} catch (IOException e) {
			fail(e.getMessage());
		}
        sendResponseMessages(context, new String[] {msgToSend}, false);
	}
	
	private static void httpRequestSender(String method, String msgExpected, String msgToSend) throws IOException {
		HttpURLConnection connection = getHttpConnection();
		sendBytesOverHttp(connection, msgToSend.getBytes(), 0, msgToSend.length());
		
		String received = new String(readBytesFromStream(connection.getInputStream()));
		assertEquals("Http connection sender did not receive the expected response code.", STATUS_CODE, connection.getResponseCode());
		assertEquals("Http connection sender did not receive the expected response description.", STATUS_DESCRIPTION, connection.getResponseMessage());
		assertEquals("Http connection sender did not receive the expected response message size.", 
				msgExpected.length(), 
				received.length());
		assertEquals("Http connection sender did not receive the expected response message.", msgExpected, received);
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
		connection.setRequestMethod((len > 0) ? "POST" : "GET");
		connection.setDoOutput(true);
		OutputStream out = connection.getOutputStream();
		out.write(b, off, len);
		out.flush();
		out.close();
	}
	
	private static void sendResponseMessages(RelayedHttpListenerContext context, String[] messages, boolean hasPause) {
		RelayedHttpListenerResponse response = context.getResponse();
		response.setStatusCode(STATUS_CODE);
		response.setStatusDescription(STATUS_DESCRIPTION);
		
		try {
			for (int i = 0; i < messages.length; i++) {
				response.getOutputStream().writeAsync(messages[i].getBytes(), 0, messages[i].length()).join();
				
				// Pause for testing the flush timeout
				if (hasPause && i < messages.length - 1) {
					Thread.sleep(ResponseStream.WRITE_BUFFER_FLUSH_TIMEOUT_MILLIS);
				}
			}
		} catch (Exception e) {
			fail(e.getMessage());
		} finally {
		    context.getResponse().close();
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
	
	private byte[] getByteSet() {
		byte[] byteSet = new byte[256];
		for (int i = 0; i < 256; i++) {
			byteSet[i] = (byte) i;
		}
		return byteSet;
	}
}
