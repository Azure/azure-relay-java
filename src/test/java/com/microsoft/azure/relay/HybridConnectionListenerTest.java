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
		listener.openAsync(Duration.ofSeconds(15)).join();
	}
	
	@Before
	public void connectListender() {
		
	}
	
	@Test
	public void openAsyncTest() {
		assertTrue(listener.isOnline());
	}
	
	@Test
	public void closeAsyncTest() throws URISyntaxException {
		listener.closeAsync();
		assertFalse(listener.isOnline());
		listener = new HybridConnectionListener(new URI(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING), tokenProvider);
		listener.openAsync(Duration.ofSeconds(15)).join();
	}
	
	@Test
	public void acceptWebSocketConnectionTest() throws URISyntaxException {
		CompletableFuture<Boolean> receivedFuture = new CompletableFuture<Boolean>();
		CompletableFuture.supplyAsync(() -> listener).thenCompose((listener) -> listener.acceptConnectionAsync()).thenAccept((websocket) -> {
			receivedFuture.complete(websocket.isOpen());
		});
		client.createConnectionAsync(null).join();
		boolean isTrue = receivedFuture.join();
		assertTrue(isTrue);
	}
	
	@Test
	public void acceptHttpConnectionTest() throws URISyntaxException, IOException {
		CompletableFuture<Boolean> receivedFuture = new CompletableFuture<Boolean>();
		listener.setRequestHandler((context) -> {
			receivedFuture.complete(true);
		});
		
		StringBuilder urlBuilder = new StringBuilder(TestUtil.RELAY_NAME_SPACE + TestUtil.CONNECTION_STRING);
		urlBuilder.replace(0, 5, "https://");
		URL url = new URL(urlBuilder.toString());
		String tokenString = tokenProvider.getTokenAsync(url.toString(), Duration.ofHours(1)).join().getToken();
		
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("ServiceBusAuthorization", tokenString);
		
		Boolean received = receivedFuture.join();
		assertTrue(received);
	}
}
