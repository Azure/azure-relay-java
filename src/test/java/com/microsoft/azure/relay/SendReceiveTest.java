package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
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
		websocketClient(smallStr, smallStr);
		listenerTask.join();
	}
	
	@Test
	public void websocketSmallSendLargeResponseTest() {
		CompletableFuture<Void> listenerTask = websocketListener(smallStr, largeStr);
		websocketClient(largeStr, smallStr);
		listenerTask.join();
	}
	
	@Test
	public void websocketLargeSendSmallResponseTest() {
		CompletableFuture<Void> listenerTask = websocketListener(largeStr, smallStr);
		websocketClient(smallStr, largeStr);
		listenerTask.join();
	}
	
	@Test
	public void websocketLargeSendLargeResponseTest() {
		CompletableFuture<Void> listenerTask = websocketListener(largeStr, largeStr);
		websocketClient(largeStr, largeStr);
		listenerTask.join();
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
        // Do something with context.Request.Url, HttpMethod, Headers, InputStream...
		RelayedHttpListenerResponse response = context.getResponse();
        response.setStatusCode(STATUS_CODE);
        response.setStatusDescription(STATUS_DESCRIPTION);
        
        String receivedText = "";
        if (context.getRequest().getInputStream() != null) {
            try (Reader reader = new BufferedReader(new InputStreamReader(context.getRequest().getInputStream(), StringUtil.UTF8))) {
                StringBuilder builder = new StringBuilder();
                int c;
                while ((c = reader.read()) != -1) {
                    builder.append((char) c);
                }
                receivedText = builder.toString();
            } 
            catch (IOException e1) {
    			fail("IO Exception when reading from HTTP");
    		}
        }
        assertEquals("Listener did not received the expected message from http connection.", msgExpected, receivedText);

        try {
			response.getOutputStream().write((msgToSend).getBytes());
		} catch (IOException e) {
			fail("IO Exception when sending to HTTP");
		}
        context.getResponse().close();
	}
	
	private static void httpRequestSender(String method, String msgExpected, String msgToSend) throws IOException {
		StringBuilder urlBuilder = new StringBuilder(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH);
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
		
		assertEquals("Http connection sender did not receive the expected response code.", STATUS_CODE, conn.getResponseCode());
		assertEquals("Http connection sender did not receive the expected response description.", STATUS_DESCRIPTION, conn.getResponseMessage());
		
		while ((inputLine = inStream.readLine()) != null) {
			builder.append(inputLine);
		}
		inStream.close();
		assertEquals("Http connection sender did not receive the expected response message.", msgExpected, builder.toString());
	}
}
