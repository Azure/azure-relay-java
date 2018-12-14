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

import org.eclipse.jetty.http.HttpStatus;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class SendReceiveTest {
	private static HybridConnectionListener listener;
	private static TokenProvider tokenProvider;
	private static HybridConnectionClient client;
	private static ClientWebSocket clientWebSocket;
	private static int statusCode = HttpStatus.ACCEPTED_202;
	
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
		client = new HybridConnectionClient(new URI(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING), tokenProvider);
		clientWebSocket = new ClientWebSocket();
		
		// Build the large string in small chunks because hardcoding a large string may not compile
		StringBuilder builder = new StringBuilder();
		String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
		for (int i = 0; i < 2000; i++) {
			builder.append(alphabet);
		}
		largeStr = builder.toString();
		listener.openAsync().join();
	}
	
	@AfterClass
	public static void cleanup() {
		listener.closeAsync().join();
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
		HybridConnectionClient client = new HybridConnectionClient(new URI(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING), tokenProvider);
		CompletableFuture<Boolean> receivedReply = new CompletableFuture<Boolean>();
		
		clientWebSocket.receiveMessageAsync().thenAccept((bytesReceived) -> {
			String msgReceived = new String(bytesReceived.array());
			receivedReply.complete(true);
			assertEquals("Websocket sender did not receive the expected reply.", msgExpected, msgReceived);
		});
		
		client.createConnectionAsync(clientWebSocket).get();
		clientWebSocket.sendAsync(msgToSend);
		assertTrue("Did not receive message from websocket sender.", receivedReply.join());
	}
	
	private static void websocketListener(String msgExpected, String msgToSend) {
		CompletableFuture<ClientWebSocket> conn = listener.acceptConnectionAsync();
		
		conn.thenAcceptAsync((websocket) -> {
			ByteBuffer bytesReceived = websocket.receiveMessageAsync().join();
			assertEquals("Websocket listener did not receive the expected message.", msgExpected, new String(bytesReceived.array()));
			websocket.sendAsync(msgToSend);
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
