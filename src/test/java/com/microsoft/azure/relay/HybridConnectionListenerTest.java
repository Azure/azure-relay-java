package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class HybridConnectionListenerTest {
	// Max # simultaneous client connections = # of cores - 1
	// Because one thread need to be reserved for listener
	private static final int MAX_CONNECTIONS_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors());
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
		if (listener != null) {
			listener.closeAsync().join();
		}
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
		});
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
	public void connectMultipleClientsTest() throws URISyntaxException {
		AtomicInteger clientConnectedCount = new AtomicInteger(0);
		AtomicInteger listenerAcceptCount = new AtomicInteger(0);

		// Set up listener to accept MAX_CONNECTIONS_COUNT connections async
		CompletableFuture<?>[] listenerConnectFutures = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];
		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			int idx = i;
			listenerConnectFutures[i] = listener.acceptConnectionAsync()
				.thenApply(listenerConnection -> {
					System.out.println(Thread.currentThread().getName() + " Listener connection " + idx + " " + listenerConnection.getTrackingContext().getTrackingId() + " accepted");
					listenerAcceptCount.incrementAndGet();
					return listenerConnection;
				});
		}
		
		// Start up MAX_CONNECTIONS_COUNT client connections async
		CompletableFuture<?>[] clientConnectFutures = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];		
		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			int idx = i;
			Instant start = Instant.now();
			System.out.println(Thread.currentThread().getName() + " Sender" + idx + " connecting");
			HybridConnectionClient hybridConnectionClient = new HybridConnectionClient(new URI(CONNECTION_URI + "?foo=bar"), tokenProvider);
			clientConnectFutures[i] = hybridConnectionClient.createConnectionAsync()
				.thenApplyAsync((connection) -> {
					System.out.println(Thread.currentThread().getName() + " Sender" + idx + " connected: " + connection.getTrackingContext().getTrackingId() + " after " + Duration.between(start, Instant.now()).toMillis());
					clientConnectedCount.incrementAndGet();
					return connection;
				})
				.whenComplete((result, ex) -> {
					if (ex != null) {
						System.out.println(idx + " Sender error: " + ex);
					}
				});
		}
		
		CompletableFuture.allOf(clientConnectFutures).join();
		CompletableFuture.allOf(listenerConnectFutures).join();
		assertEquals(MAX_CONNECTIONS_COUNT, clientConnectedCount.get());
		assertEquals(MAX_CONNECTIONS_COUNT, listenerAcceptCount.get());
				
		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			HybridConnectionChannel listenerConnection = (HybridConnectionChannel) listenerConnectFutures[i].join();
			int idx = i;
			listenerConnection.readAsync(Duration.ofSeconds(20))
				.thenCompose(readBuffer -> {
					System.out.println(idx + " Listener connection " + listenerConnection.getTrackingContext().getTrackingId() + " received " + readBuffer.remaining() + " byte(s)");
					return listenerConnection.writeAsync(readBuffer);
				});
		}
			
		CompletableFuture<?>[] clientSendFutures = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];
		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			HybridConnectionChannel clientConnection = (HybridConnectionChannel) clientConnectFutures[i].join();
			assertTrue(clientConnection.isOpen());
			byte[] array = new byte[1];
			array[0] = (byte)i;
			final int idx = i;
			clientSendFutures[i] = clientConnection.writeAsync(ByteBuffer.wrap(array))
				.thenCompose(unused -> clientConnection.readAsync(Duration.ofSeconds(20)))
				.thenAccept(readBuffer -> {
					System.out.println(idx + " Sender connection " + clientConnection.getTrackingContext().getTrackingId() + " received " + readBuffer.remaining() + " byte(s)");
					assertEquals(idx + " Sender connection bytes read", 1, readBuffer.remaining());
					assertEquals(idx + " Sender connection byte value", idx, readBuffer.get());
				});
		}

		CompletableFuture.allOf(clientSendFutures).join();
		
		// Close client side connections async
		CompletableFuture<?>[] listenerCloseFutures = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];
		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			HybridConnectionChannel listenerConnection = (HybridConnectionChannel) listenerConnectFutures[i].join();
			int idx = i;
			String trackingId = listenerConnection.getTrackingContext().getTrackingId();
			listenerCloseFutures[i] = listenerConnection.readAsync(Duration.ofSeconds(20))
				.thenCompose(readBuffer -> {
					System.out.println(idx + " Listener connection " + trackingId + " received " + readBuffer.remaining() + " byte(s)");
					return listenerConnection.closeAsync()
						.whenComplete((result, ex) -> {
							System.out.println(idx + " Listener connection " + trackingId + " closed " + ex);						
						});
				});
		}

		// Close client side connections async
		CompletableFuture<?>[] clientCloseFutures = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];
		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			HybridConnectionChannel clientConnection = (HybridConnectionChannel) clientConnectFutures[i].join();
			int idx = i;
			String trackingId = clientConnection.getTrackingContext().getTrackingId();
			System.out.println(idx + " Sender connection " + trackingId + " closing");						
			clientCloseFutures[i] = clientConnection.closeAsync()
				.whenComplete((result, ex) -> {
					System.out.println(idx + " Sender connection " + trackingId + " closed " + ex);						
				});
		}
		
		CompletableFuture.allOf(clientCloseFutures).join();
		CompletableFuture.allOf(listenerCloseFutures).join();
	}
}
