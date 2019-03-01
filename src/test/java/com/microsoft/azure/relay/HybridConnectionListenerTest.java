package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpStatus;
import org.junit.BeforeClass;
import org.junit.Test;

import com.microsoft.azure.relay.HybridConnectionListener.ControlConnection;

public class HybridConnectionListenerTest {
	private static final int MAX_CONNECTIONS_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() * 2);
	private static final AutoShutdownScheduledExecutor EXECUTOR = AutoShutdownScheduledExecutor.Create();
	private static TokenProvider tokenProvider;
	private static HybridConnectionClient client;
	private static URI CONNECTION_URI;
	
	@BeforeClass
	public static void init() throws URISyntaxException, RelayException {
		CONNECTION_URI = new URI(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH);
		tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(TestUtil.KEY_NAME, TestUtil.KEY);
		client = new HybridConnectionClient(CONNECTION_URI, tokenProvider);
	}
	
	@Test
	public void openAndCloseTest() {
		HybridConnectionListener listener = new HybridConnectionListener(CONNECTION_URI, tokenProvider);
		AtomicBoolean onlineHandlerCalled = new AtomicBoolean(false);
		AtomicBoolean offlineHandlerCalled = new AtomicBoolean(false);
		
		listener.setOnlineHandler(() -> {
			onlineHandlerCalled.set(true);
		});
		
		listener.setOfflineHandler(ex -> {
			assertNull("There should not have been an exception when closing the listener normally.", ex);
			offlineHandlerCalled.set(true);
		});
		
		listener.openAsync(Duration.ofSeconds(15)).join();
		assertTrue("Listener failed to open.", listener.isOnline());
		assertTrue("Listener open handler was not called", onlineHandlerCalled.get());
		
		listener.close();
		assertFalse("Listner should be closed", listener.isOnline());
		assertTrue("Listener offline handler was not called", offlineHandlerCalled.get());
	}
	
	@Test
	public void webSocketConnectionTest() throws InterruptedException, ExecutionException, TimeoutException {
		int waitMS = 500;
		HybridConnectionListener listener = new HybridConnectionListener(CONNECTION_URI, tokenProvider);
		listener.openAsync(Duration.ofSeconds(15)).join();
		
		// Client initiates closing
		CompletableFuture<Void> clientClosing = listener.acceptConnectionAsync().thenCombineAsync(client.createConnectionAsync(), (rendezvousConnection, clientConnection) -> {
			assertTrue("client connection should be open", clientConnection != null && clientConnection.isOpen());
			assertTrue("rendezvous connection should be open", rendezvousConnection != null && rendezvousConnection.isOpen());
			clientConnection.closeAsync().join();
			
			assertTrue("listener should still be open", listener.isOnline());
			assertFalse("client connection should be closed", clientConnection.isOpen());
			return rendezvousConnection;
		}).thenCompose(rendezvousConnection -> {
			return CompletableFutureUtil.delayAsync(Duration.ofMillis(waitMS), EXECUTOR).whenComplete(($void, ex) -> {
				assertFalse("rendezvous connection should be closed implicitly when client connection closes", rendezvousConnection.isOpen());
			});
		});
		
		// Rendezvous initiates closing
		CompletableFuture<Void> rendezvousClosing = client.createConnectionAsync().thenCombineAsync(listener.acceptConnectionAsync(), (rendezvousConnection, clientConnection) -> {
			assertTrue("client connection should be open", clientConnection != null && clientConnection.isOpen());
			assertTrue("rendezvous connection should be open", rendezvousConnection != null && rendezvousConnection.isOpen());
			rendezvousConnection.closeAsync().join();
			
			assertTrue("listener should still be open", listener.isOnline());
			assertFalse("rendezvous connection should be closed", rendezvousConnection.isOpen());
			return clientConnection;
		}).thenCompose(clientConnection -> {
			return CompletableFutureUtil.delayAsync(Duration.ofMillis(waitMS), EXECUTOR).whenComplete(($void, ex) -> {
				assertFalse("client connection should be closed implicitly when rendezvous connection closes", clientConnection.isOpen());
			});
		});
		
		CompletableFuture.allOf(clientClosing, rendezvousClosing).whenComplete(($void, ex) -> {
			if (ex != null) {
				fail(ex.getMessage());
			}	
			listener.close();
		}).join();
	}
	
	@Test
	public void acceptHandlerTest() throws InterruptedException, ExecutionException, TimeoutException {
		HybridConnectionListener listener = new HybridConnectionListener(CONNECTION_URI, tokenProvider);
		AtomicInteger handlerExecuted = new AtomicInteger(0);
		listener.openAsync().join();
		
		// Handler accepts the connection
		listener.setAcceptHandler(context -> {
			handlerExecuted.incrementAndGet();
			return true;
		});
		
		client.createConnectionAsync().whenComplete((clientConnection, ex) -> {
			assertTrue("clientConnection should have been accepted", clientConnection != null && clientConnection.isOpen());
		});
		listener.acceptConnectionAsync().whenComplete((rendezvousConnection, ex) -> {
			assertTrue("rendezvousConnection should have been accepted", rendezvousConnection != null && rendezvousConnection.isOpen());
			rendezvousConnection.closeAsync();
		}).join();
		
		// Handler rejects the connection
		AtomicReference<RelayedHttpListenerContext> httpContext = new AtomicReference<RelayedHttpListenerContext>();
		listener.setAcceptHandler(context -> {
			httpContext.set(context);
			handlerExecuted.incrementAndGet();
			return false;
		});
		CompletableFuture<HybridConnectionChannel> acceptConnectionTask = listener.acceptConnectionAsync();
		
		client.createConnectionAsync().handle((clientConnection, ex) -> {
			assertNull("clientConnection should not have been accepted", clientConnection);
			assertNotNull("Rejection should have thrown.", ex);

			RelayedHttpListenerResponse response = httpContext.get().getResponse();
			assertEquals("The rejection response code is incorrect", HttpStatus.BAD_REQUEST_400, response.getStatusCode());
			assertEquals("The rejection response description is incorrect", "Rejected by user code", response.getStatusDescription());
			assertEquals("Both handlers should have been run", 2, handlerExecuted.get());
			
			listener.close();
			return null;
		}).join();
		
		acceptConnectionTask.whenComplete((connection, ex) -> {
			assertNull("There shouldn't be a connection established successfully", connection);
		}).join();
	}
	
	@Test
	public void requestHandlerTest() throws IOException {
		HybridConnectionListener listener = new HybridConnectionListener(CONNECTION_URI, tokenProvider);
		AtomicBoolean handlerExecuted = new AtomicBoolean(false);
		int status = HttpStatus.ACCEPTED_202;
		
		listener.setRequestHandler((context) -> {
			handlerExecuted.set(context != null && context.getRequest() != null);
			RelayedHttpListenerResponse response = context.getResponse();
            response.setStatusCode(status);
            
			try {
				response.getOutputStream().write(0);
			} catch (IOException e) {
				e.printStackTrace();
			}
			context.getResponse().close();
		});
		
		listener.openAsync(Duration.ofSeconds(15)).join();
		StringBuilder urlBuilder = new StringBuilder(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH);
		urlBuilder.replace(0, 5, "https://");
		URL url = new URL(urlBuilder.toString());
		String tokenString = tokenProvider.getTokenAsync(url.toString(), Duration.ofHours(1)).join().getToken();
		
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("ServiceBusAuthorization", tokenString);

		assertEquals("Response did not have the expected response code.", status, conn.getResponseCode());
		assertTrue("Listener failed to accept connections from sender in http mode.", handlerExecuted.get());
		
		listener.close();
	}
	
	@Test
	public void listenerReconnectionTest() {
		HybridConnectionListener listener = new HybridConnectionListener(CONNECTION_URI, tokenProvider);
		AtomicBoolean handlerExecuted = new AtomicBoolean(false);
		
		listener.setConnectingHandler((ex) -> {
			assertTrue("Exception should be ConnectionLostException.", ex != null && ex instanceof ConnectionLostException);
			handlerExecuted.set(true);
		});
		
		listener.openAsync(Duration.ofSeconds(15)).join();
		ControlConnection controlConnection = listener.getControlConnection();
		controlConnection.close(); // This bypasses listener.close(), simulating an unexptected close
		
		CompletableFuture<Void> closeTask = CompletableFutureUtil.delayAsync(Duration.ofMillis(1000), EXECUTOR).thenRun(() -> {
			assertTrue("listener should be reconnected now", listener.isOnline());
			listener.close();
		});
		
		assertFalse("listener should be disconnected temporarily for now", listener.isOnline());
		assertTrue("The reconnecting handler was not called", handlerExecuted.get());
		closeTask.join();
	}
	
	@Test
	public void connectMultipleClientsTest() throws URISyntaxException {
		HybridConnectionListener listener = new HybridConnectionListener(CONNECTION_URI, tokenProvider);
		listener.openAsync(Duration.ofSeconds(15)).join();
		AtomicInteger clientConnectedCount = new AtomicInteger(0);
		AtomicInteger listenerAcceptCount = new AtomicInteger(0);

		// Set up listener to accept MAX_CONNECTIONS_COUNT connections async
		CompletableFuture<?>[] listenerConnectFutures = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];
		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			listenerConnectFutures[i] = listener.acceptConnectionAsync()
				.thenApply(listenerConnection -> {
					listenerAcceptCount.incrementAndGet();
					return listenerConnection;
				});
		}
		
		// Start up MAX_CONNECTIONS_COUNT client connections async
		CompletableFuture<?>[] clientConnectFutures = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];		
		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			int idx = i;
			HybridConnectionClient hybridConnectionClient = new HybridConnectionClient(new URI(CONNECTION_URI + "?foo=bar"), tokenProvider);
			clientConnectFutures[i] = hybridConnectionClient.createConnectionAsync()
				.thenApplyAsync((connection) -> {
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
			listenerConnection.readAsync(Duration.ofSeconds(20))
				.thenCompose(readBuffer -> {
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
					assertEquals(idx + " Sender connection bytes read", 1, readBuffer.remaining());
					assertEquals(idx + " Sender connection byte value", idx, readBuffer.get());
				});
		}

		CompletableFuture.allOf(clientSendFutures).join();
		
		// Close client side connections async
		CompletableFuture<?>[] listenerCloseFutures = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];
		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			HybridConnectionChannel listenerConnection = (HybridConnectionChannel) listenerConnectFutures[i].join();
			listenerCloseFutures[i] = listenerConnection.readAsync(Duration.ofSeconds(20))
				.thenCompose(readBuffer -> {
					return listenerConnection.closeAsync();
				});
		}

		// Close client side connections async
		CompletableFuture<?>[] clientCloseFutures = new CompletableFuture<?>[MAX_CONNECTIONS_COUNT];
		for (int i = 0; i < MAX_CONNECTIONS_COUNT; i++) {
			HybridConnectionChannel clientConnection = (HybridConnectionChannel) clientConnectFutures[i].join();
			clientCloseFutures[i] = clientConnection.closeAsync();
		}
		
		CompletableFuture.allOf(clientCloseFutures).join();
		CompletableFuture.allOf(listenerCloseFutures).join();
		listener.close();
	}
}
