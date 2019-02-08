package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class HybridConnectionListenerTest {
	// Max # simultaneous client connections = # of cores - 1
	// Because one thread need to be reserved for listener
	private static final int MAX_CONNECTIONS_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() + 1);
	private static HybridConnectionListener listener;
	private static TokenProvider tokenProvider;
	private static HybridConnectionClient client;
	private static URI CONNECTION_URI;
	
	@BeforeClass
	public static void init() throws URISyntaxException, RelayException {
		CONNECTION_URI = new URI(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH);
		tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(TestUtil.KEY_NAME, TestUtil.KEY);
		listener = new HybridConnectionListener(CONNECTION_URI, tokenProvider);
		client = new HybridConnectionClient(CONNECTION_URI, tokenProvider);
		listener.openAsync(Duration.ofSeconds(15)).join();
	}
	
	@AfterClass
	public static void cleanup() {
		listener.closeAsync().join();
	}
	
	@Test
	public void openAsyncTest() {
		assertTrue("Listener failed to open.", listener.isOnline());
	}
	
	@Test
	public void acceptWebSocketConnectionTest() {
		CompletableFuture<Boolean> checkSocketConnectionTask = new CompletableFuture<Boolean>();
		CompletableFuture<HybridConnectionChannel> conn = listener.acceptConnectionAsync();
		CompletableFuture<HybridConnectionChannel> clientConnectionTask = client.createConnectionAsync();
		conn.thenAccept((connection) -> {
			checkSocketConnectionTask.complete(true);
			clientConnectionTask.thenAccept(clientConnection -> clientConnection.closeAsync());
			connection.closeAsync();
		}).join();
		assertTrue("Listener failed to accept connections from sender in webSocket mode.", checkSocketConnectionTask.join());
	}
	
	@Test
	public void acceptHttpConnectionTest() throws IOException {
		CompletableFuture<Boolean> checkHttpConnectionTask = new CompletableFuture<Boolean>();
		int status = HttpStatus.ACCEPTED_202;

		listener.setRequestHandler((context) -> {
			RelayedHttpListenerResponse response = context.getResponse();
            response.setStatusCode(status);
            
			checkHttpConnectionTask.complete(true);
			try {
				response.getOutputStream().write(0);
			} catch (IOException e) {
				e.printStackTrace();
			}
			context.getResponse().close();
		});
		
		StringBuilder urlBuilder = new StringBuilder(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH);
		urlBuilder.replace(0, 5, "https://");
		URL url = new URL(urlBuilder.toString());
		String tokenString = tokenProvider.getTokenAsync(url.toString(), Duration.ofHours(1)).join().getToken();
		
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("ServiceBusAuthorization", tokenString);

		assertEquals("Response did not have the expected response code.", status, conn.getResponseCode());
		assertTrue("Listener failed to accept connections from sender in http mode.", checkHttpConnectionTask.join());	
	}
	
	@Test
	public void connectMultipleClientsTest() {
		AtomicInteger clientConnectedCount = new AtomicInteger(0);
		AtomicInteger listenersConnectedCount = new AtomicInteger(0);
		CompletableFuture<Boolean> allClientsConnected = new CompletableFuture<Boolean>();
		CompletableFuture<Boolean> allListenersConnected = new CompletableFuture<Boolean>();

		CompletableFuture<?>[] listenerConnections = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];
		CompletableFuture<?>[] clientConnections = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];
		
		CompletableFutureUtil.timedRunAsync(Duration.ofSeconds(MAX_CONNECTIONS_COUNT), () -> {
			for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
				listenerConnections[i] = listener.acceptConnectionAsync();
				listenerConnections[i].thenRun(() -> {
					System.out.println("listener accepted");
					if (listenersConnectedCount.incrementAndGet() == MAX_CONNECTIONS_COUNT) {
						allListenersConnected.complete(true);
					}
				});
			}
		});

		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			clientConnections[i] = new HybridConnectionClient(CONNECTION_URI, tokenProvider).createConnectionAsync();
			clientConnections[i].thenRun(() -> {
				if (clientConnectedCount.incrementAndGet() == MAX_CONNECTIONS_COUNT) {
					allClientsConnected.complete(true);
				}
			});
		}
		
		CompletableFutureUtil.timedRunAsync(null, () -> {
			CompletableFuture.allOf(allClientsConnected, allListenersConnected).thenRun(() -> {
				assertEquals(MAX_CONNECTIONS_COUNT, clientConnectedCount.get());
				assertEquals(MAX_CONNECTIONS_COUNT, listenersConnectedCount.get());

				for (CompletableFuture<?> listenerFuture : listenerConnections) {
					HybridConnectionChannel connection = (HybridConnectionChannel) listenerFuture.join();
					assertTrue(connection.isOpen());
					connection.closeAsync();
				}
				for (CompletableFuture<?> clientFuture : clientConnections) {
					HybridConnectionChannel connection = (HybridConnectionChannel) clientFuture.join();
					connection.closeAsync();
				}
			}).join();
		}).join();
	}
}
