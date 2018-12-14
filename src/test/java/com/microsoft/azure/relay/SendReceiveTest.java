package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;

import org.eclipse.jetty.http.HttpStatus;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class SendReceiveTest {
	private static HybridConnectionListener listener;
	private static TokenProvider tokenProvider;
	private static HybridConnectionClient client;
	private static ClientWebSocket clientWebSocket;
	private static int statusCode = HttpStatus.ACCEPTED_202;
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
		listener = new HybridConnectionListener(new URI(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING), tokenProvider);

		
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
		
		client = new HybridConnectionClient(new URI(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING), tokenProvider);
		clientWebSocket = new ClientWebSocket();
	}
	
	@Test
	public void websocketSmallSendSmallResponseTest() throws URISyntaxException, InterruptedException, ExecutionException, IOException {
		websocketListener(smallStr, smallStr);
		websocketClient(smallStr, smallStr);
	}
	
	@Test
	public void websocketSmallSendLargeResponseTest() throws URISyntaxException, InterruptedException, ExecutionException, IOException {
		websocketListener(smallStr, largeStr);
		websocketClient(largeStr, smallStr);
	}
	
	@Test
	public void websocketLargeSendSmallResponseTest() throws URISyntaxException, InterruptedException, ExecutionException, IOException {
		websocketListener(largeStr, smallStr);
		websocketClient(smallStr, largeStr);
	}
	
	@Test
	public void websocketLargeSendLargeResponseTest() throws URISyntaxException, InterruptedException, ExecutionException, IOException {
		websocketListener(largeStr, largeStr);
		websocketClient(largeStr, largeStr);
	}
	
	@Test
	public void websocketRepeatedSendReceiveTest() {
		int timesToRepeat = 3;
		CompletableFuture<Integer> senderReceiveCount = new CompletableFuture<Integer>();
		CompletableFuture<Integer> listenerReceiveCount = new CompletableFuture<Integer>();
		
		listener.acceptConnectionAsync().thenAcceptAsync((websocket) -> {
			for (int i = 1; i <= timesToRepeat; i++) {
				websocket.receiveMessageAsync().join();
				if (i == timesToRepeat) {
					listenerReceiveCount.complete(i);
				}
				websocket.sendAsync("hi");
			}
		});
		
		client.createConnectionAsync(clientWebSocket).thenRun(() -> {
			clientWebSocket.sendAsync("hi");
			for (int i = 1; i <= timesToRepeat; i++) {
				clientWebSocket.receiveMessageAsync().join();
				if (i == timesToRepeat) {
					senderReceiveCount.complete(i);
				}
				if (i < timesToRepeat) {
					clientWebSocket.sendAsync("hi");
				}
			}
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
				websocket.receiveMessageAsync().thenRun(() -> {
					listenerReceiveCount++;
					websocket.sendAsync("hi").join();
					websocket.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "Normal closure from listener")).join();
				});
			});
			
			HybridConnectionClient newClient = new HybridConnectionClient(new URI(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING), tokenProvider);
			newClient.createConnectionAsync().thenAccept((socket) -> {
				socket.sendAsync("hi").join();
				socket.receiveMessageAsync().thenRun(() -> {
					senderReceiveCount++;
					if (senderReceiveCount >= numberOfSenders) {
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
		senderReceiveCount = 0;
		listenerReceiveCount = 0;
	}
	
	
	@Test
	public void httpGETAndSmallResponseTest() throws IOException, InterruptedException, ExecutionException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, emptyStr, smallStr));
        httpRequestSender("GET", smallStr, emptyStr);
	}
	
	@Test
	public void httpGETAndLargeResponseTest() throws IOException, InterruptedException, ExecutionException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, emptyStr, largeStr));
        httpRequestSender("GET", largeStr, emptyStr);
	}
	
	@Test
	public void httpSmallPOSTAndSmallResponseTest() throws IOException, InterruptedException, ExecutionException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, smallStr, smallStr));
        httpRequestSender("POST", smallStr, smallStr);
	}
	
	@Test
	public void httpSmallPOSTAndLargeResponseTest() throws IOException, InterruptedException, ExecutionException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, smallStr, largeStr));
        httpRequestSender("POST", largeStr, smallStr);
	}
	
	@Test
	public void httpLargePOSTAndSmallResponseTest() throws IOException, InterruptedException, ExecutionException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, largeStr, smallStr));
        httpRequestSender("POST", smallStr, largeStr);
	}
	
	@Test
	public void httpLargePOSTAndLargeResponseTest() throws IOException, InterruptedException, ExecutionException {
		listener.setRequestHandler((context) -> httpRequestHandler(context, largeStr, largeStr));
        httpRequestSender("POST", largeStr, largeStr);
	}
	
	private static void websocketClient(String msgExpected, String msgToSend) throws URISyntaxException, InterruptedException, ExecutionException, IOException {
		CompletableFuture<Boolean> receivedReply = new CompletableFuture<Boolean>();
		
		clientWebSocket.receiveMessageAsync().thenAccept((bytesReceived) -> {
			String msgReceived = new String(bytesReceived.array());
			receivedReply.complete(true);
			assertEquals("Websocket sender did not receive the expected reply.", msgExpected, msgReceived);
		});
		
		client.createConnectionAsync(clientWebSocket).thenRun(() -> clientWebSocket.sendAsync(msgToSend));
		assertTrue("Did not receive message from websocket sender.", receivedReply.join());
	}
	
	private static void websocketListener(String msgExpected, String msgToSend) {
		CompletableFuture<ClientWebSocket> conn = listener.acceptConnectionAsync();
		
		conn.thenAcceptAsync((websocket) -> {
			ByteBuffer bytesReceived = websocket.receiveMessageAsync().join();
			assertEquals("Websocket listener did not receive the expected message.", msgExpected, new String(bytesReceived.array()));
			websocket.sendAsync(msgToSend).join();
			websocket.closeAsync(new CloseReason(CloseCodes.NORMAL_CLOSURE, "Listener closing normally."));
		});
	}
	
	private static void httpRequestHandler(RelayedHttpListenerContext context, String msgExpected, String msgToSend) {
        // Do something with context.Request.Url, HttpMethod, Headers, InputStream...
		RelayedHttpListenerResponse response = context.getResponse();
        response.setStatusCode(statusCode);
        response.setStatusDescription("OK");

        String receivedText = (context.getRequest().getInputStream() != null) ? new String(context.getRequest().getInputStream().array()) : "";
        assertEquals("Listener did not received the expected message from http connection.", msgExpected, receivedText);

        try {
			response.getOutputStream().write((msgToSend).getBytes());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        context.getResponse().close();
	}
	
	private static void httpRequestSender(String method, String msgExpected, String msgToSend) throws IOException, InterruptedException, ExecutionException {
		StringBuilder urlBuilder = new StringBuilder(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING);
		urlBuilder.replace(0, 5, "https://");
		URL url = new URL(urlBuilder.toString());
		String tokenString = tokenProvider.getTokenAsync(url.toString(), Duration.ofHours(1)).join().getToken();
		
		
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod(method);
		conn.setRequestProperty("ServiceBusAuthorization", tokenString);
		
		String message = msgToSend;
		conn.setDoOutput(true);
		OutputStreamWriter out = new OutputStreamWriter(conn.getOutputStream());
		out.write(message, 0, message.length());
		out.flush();
		out.close();
		
		String inputLine;
		StringBuilder builder = new StringBuilder();
		BufferedReader inStream = new BufferedReader(new InputStreamReader(conn.getInputStream()));
		
		assertEquals("Http connection sender did not receive the expected response code.", statusCode, conn.getResponseCode());
		while ((inputLine = inStream.readLine()) != null) {
			builder.append(inputLine);
		}
		inStream.close();
		assertEquals("Http connection sender did not receive the expected response message.", msgExpected, builder.toString());
	}
}
