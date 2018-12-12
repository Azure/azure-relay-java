package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.HttpStatus;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class HybridConnectionListenerTest {
	private static HybridConnectionListener listener;
	private static TokenProvider tokenProvider;
	private static HybridConnectionClient client;
	private static ClientWebSocket clientWebSocket;
	
	@BeforeClass
	public static void init() throws URISyntaxException {
		tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(TestUtil.KEY_NAME, TestUtil.KEY);
		listener = new HybridConnectionListener(new URI(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING), tokenProvider);
		client = new HybridConnectionClient(new URI(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING), tokenProvider);
		clientWebSocket = new ClientWebSocket();
	}
	
	@AfterClass
	public static void cleanup() {
		listener.closeAsync();
		client.close();
	}
	
	@Before
	public void connectListener() throws URISyntaxException {
		listener = new HybridConnectionListener(new URI(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING), tokenProvider);
		listener.openAsync(Duration.ofSeconds(15)).join();
	}
	
	@After
	public void closeListener() {
		listener.closeAsync();
	}
	
	@Test
	public void openAsyncTest() {
		assertTrue(listener.isOnline());
	}
	
	@Test
	public void closeAsyncTest() throws URISyntaxException {
		listener.closeAsync();
		assertFalse(listener.isOnline());
	}
	
	@Test
	public void acceptWebSocketConnectionTest() throws URISyntaxException {
		CompletableFuture<Boolean> checkSocketConnectionTask = new CompletableFuture<Boolean>();
		CompletableFuture<ClientWebSocket> conn = listener.acceptConnectionAsync();
		client.createConnectionAsync(null);
		conn.thenAccept((websocket) -> {
			checkSocketConnectionTask.complete(websocket.isOpen());
		});
		assertTrue(checkSocketConnectionTask.join());
	}
	
	@Test
	public void acceptHttpConnectionTest() throws URISyntaxException, IOException {
		CompletableFuture<Boolean> receivedFuture = new CompletableFuture<Boolean>();
		int status = HttpStatus.ACCEPTED_202;

		listener.setRequestHandler((context) -> {
			RelayedHttpListenerResponse response = context.getResponse();
            response.setStatusCode(status);
            
			receivedFuture.complete(true);
			try {
				response.getOutputStream().write(0);
			} catch (IOException e) {
				e.printStackTrace();
			}
			context.getResponse().close();
		});
		
		StringBuilder urlBuilder = new StringBuilder(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING);
		urlBuilder.replace(0, 5, "https://");
		URL url = new URL(urlBuilder.toString());
		String tokenString = tokenProvider.getTokenAsync(url.toString(), Duration.ofHours(1)).join().getToken();
		
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("ServiceBusAuthorization", tokenString);

		assertEquals("Response did not have the expected response code.", status, conn.getResponseCode());
		assertTrue("HTTP request was not received by listener.", receivedFuture.join());	
	}
}
