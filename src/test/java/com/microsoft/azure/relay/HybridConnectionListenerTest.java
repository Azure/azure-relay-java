package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.microsoft.azure.relay.HybridConnectionListener.ControlConnection;

public class HybridConnectionListenerTest {
	private static final int MAX_CONNECTIONS_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() * 2);
	private static final AutoShutdownScheduledExecutor EXECUTOR = AutoShutdownScheduledExecutor.Create();
	private static URI CONNECTION_URI;
	private TokenProvider tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(TestUtil.KEY_NAME, TestUtil.KEY);
	private HybridConnectionClient client = new HybridConnectionClient(CONNECTION_URI, tokenProvider);
	private HybridConnectionListener listener;
	
	@BeforeClass
	public static void init() throws URISyntaxException, RelayException {
		CONNECTION_URI = new URI(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH);
	}
	
	@Before
	public void beforeEachTest() {
		listener = new HybridConnectionListener(CONNECTION_URI, tokenProvider);
	}
	
	@After
	public void afterEachTest() {
		listener.close();
	}
	
	@Test
	public void openAndCloseTest() {
		AtomicInteger onlineHandlerCalled = new AtomicInteger(0);
		AtomicInteger offlineHandlerCalled = new AtomicInteger(0);
		
		listener.setOnlineHandler(() -> {
			onlineHandlerCalled.incrementAndGet();
		});
		
		listener.setOfflineHandler(ex -> {
			assertNull("There should not have been an exception when closing the listener normally.", ex);
			offlineHandlerCalled.incrementAndGet();
		});
		
		listener.openAsync(Duration.ofSeconds(15)).join();
		assertTrue("Listener failed to open.", listener.isOnline());
		assertEquals("Listener open handler was not called exactly once", 1, onlineHandlerCalled.get());
		
		listener.close();
		assertFalse("Listener should be closed", listener.isOnline());
		assertEquals("Listener offline handler was not called exactly once", 1, offlineHandlerCalled.get());
	}
	
	@Test
	public void customHeadersTest() throws InterruptedException, ExecutionException, TimeoutException {
		String key = "GoodHeader";
		String value = "GoodValue";
		Map<String, List<String>> customHeaders = new HashMap<String, List<String>>();
		customHeaders.put(key, Arrays.asList(new String[]{value}));
		
		CompletableFuture<Void> requestReceived = new CompletableFuture<Void>();
		listener.openAsync(Duration.ofSeconds(15)).join();
		listener.setAcceptHandler((context) -> {
			try {
				assertNotNull("Listener should have received a valid http context.", context);
				assertNotNull("Listener should have received a valid http request.", context.getRequest());
				
				Map<String, String> headers = context.getRequest().getHeaders();
				assertTrue(headers.containsKey(key));
				assertEquals(value, headers.get(key));
				requestReceived.complete(null);
			} catch (Throwable ex) {
				requestReceived.completeExceptionally(ex);
			} finally {
				context.getResponse().close();
			}
			return true;
		});
		client.createConnectionAsync(customHeaders).thenAccept(connection -> {
			connection.closeAsync().join();
		}).join();
		requestReceived.get(10, TimeUnit.SECONDS);
		
		String badKey = "Bad Header";
		String badValue = "Bad:Value";
		customHeaders.put(badKey, Arrays.asList(new String[]{badValue}));
		Assertions.assertCFThrows(UpgradeException.class, client.createConnectionAsync(customHeaders).thenAccept(connection -> connection.closeAsync().join()));
	}
	
	@Test
	public void webSocketConnectionTest() throws InterruptedException, ExecutionException, TimeoutException {
		int waitMS = 500;
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
		
		CompletableFuture.allOf(clientClosing, rendezvousClosing).join();
	}
	
	@Test
	public void acceptHandlerTest() throws InterruptedException, ExecutionException, TimeoutException {
		AtomicInteger handlerExecuted = new AtomicInteger(0);
		listener.openAsync().join();
		
		// Handler accepts the connection
		listener.setAcceptHandler(context -> {
			handlerExecuted.incrementAndGet();
			return true;
		});
		
		CompletableFuture<HybridConnectionChannel> clientConnectionTask = 
			client.createConnectionAsync().whenComplete((clientConnection, ex) -> {
				assertTrue("clientConnection should have been accepted", clientConnection != null && clientConnection.isOpen());
			});
		CompletableFuture<HybridConnectionChannel> rendezvousConnectionTask = 
			listener.acceptConnectionAsync().whenComplete((rendezvousConnection, ex) -> {
				assertTrue("rendezvousConnection should have been accepted", rendezvousConnection != null && rendezvousConnection.isOpen());
				rendezvousConnection.closeAsync();
			});
		CompletableFuture.allOf(clientConnectionTask, rendezvousConnectionTask).join();
		
		// Handler rejects the connection
		listener.setAcceptHandler(context -> {
			handlerExecuted.incrementAndGet();
			return false;
		});
		CompletableFuture<HybridConnectionChannel> acceptConnectionTask = listener.acceptConnectionAsync();
		
		client.createConnectionAsync().handle((clientConnection, ex) -> {
			assertNull("clientConnection should not have been accepted", clientConnection);
			assertNotNull("Rejection should have thrown.", ex);
			
			Throwable cause = ex.getCause();
			assertTrue("The exception should be caused by failure to upgrade", cause instanceof UpgradeException);
			assertEquals("The response code does not match expected", HttpStatus.BAD_REQUEST_400, ((UpgradeException)cause).getResponseStatusCode());
			assertEquals("Both handlers should have been run", 2, handlerExecuted.get());
			
			return null;
		}).join();
		
		listener.close();
		
		acceptConnectionTask.whenComplete((connection, ex) -> {
			assertNull("There shouldn't be a connection established successfully", connection);
			assertNull("There shouldn't be an exception thrown", ex);
		}).join();
	}
	
	@Test
	public void requestHandlerTest() throws IOException {
		AtomicInteger handlerExecuted = new AtomicInteger(0);
		int status = HttpStatus.ACCEPTED_202;
		String headerKey = "foo";
		String headerVal = "bar";
		
		listener.setRequestHandler((context) -> {
			if (context != null && context.getRequest() != null) {
				handlerExecuted.incrementAndGet();
			}
			RelayedHttpListenerResponse response = context.getResponse();
			response.setStatusCode(status);
			response.getHeaders().put(headerKey, headerVal);
            
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
		assertEquals("Response did not contain the expected header", headerVal, conn.getHeaderField(headerKey));
		assertEquals("Listener failed to accept connections exactly once from sender in http mode.", 1, handlerExecuted.get());
	}
	
	@Test
	public void parseRequestPathAndQueryTest() throws IOException, URISyntaxException {
		CompletableFuture<Void> requestReceived = new CompletableFuture<Void>();
		String extraPath = "/extraPath/extraPath2";
		String extraQuery = "queryKey=queryValue&queryKey2=queryValue;"; // mixing in a special char just for fun
		
		listener.setRequestHandler((context) -> {
			try {
				assertNotNull("Listener should have received a valid http context.", context);
				assertNotNull("Listener should have received a valid http request.", context.getRequest());
				
				URI requestUri = context.getRequest().getUri();
				assertNotNull("Listener should have a URI from request", requestUri);
				assertEquals(
						"The path wasn't the expected value", 
						"/" + TestUtil.ENTITY_PATH + extraPath, 
						requestUri.getPath()
				);
				assertEquals("The query wasn't the expected value", extraQuery, requestUri.getQuery());
				
				requestReceived.complete(null);
			} catch (Throwable ex) {
				requestReceived.completeExceptionally(ex);
			} finally {
				context.getResponse().close();
			}
		});
		
		listener.openAsync(Duration.ofSeconds(15)).join();
		StringBuilder urlBuilder = new StringBuilder(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH);
		urlBuilder.replace(0, 5, "https://");
		urlBuilder.append(extraPath);
		urlBuilder.append("?").append(extraQuery);
		
		URL url = new URI(urlBuilder.toString()).toURL();
		String tokenString = tokenProvider.getTokenAsync(url.toString(), Duration.ofHours(1)).join().getToken();
		
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("ServiceBusAuthorization", tokenString);

		conn.getResponseCode();
		try {
			assertNull(requestReceived.get(30, TimeUnit.SECONDS));
		} catch (Exception e) {
			fail(e.getMessage());
		}
	}
	
	@Test
	public void listenerReconnectionTest() {
		AtomicInteger handlerExecuted = new AtomicInteger(0);
		AtomicReference<Throwable> exception = new AtomicReference<>(new Exception());
		listener.setConnectingHandler(ex -> {
			exception.set(ex);
			handlerExecuted.incrementAndGet();
		});
		
		listener.openAsync(Duration.ofSeconds(15)).join();
		listener.injectFault(new ConnectionLostException());
		assertFalse("listener should be disconnected temporarily for now", listener.isOnline());
		CompletableFuture<Void> reconnectTask = CompletableFutureUtil.delayAsync(Duration.ofMillis(1000), EXECUTOR).thenRun(() -> {
			assertTrue("listener should be reconnected now", listener.isOnline());
		});

		assertEquals("The reconnecting handler was not called exactly once.", 1, handlerExecuted.get());
		assertTrue("Exception should be ConnectionLostException.", exception.get() instanceof ConnectionLostException);	
		reconnectTask.join();
	}
	
	// The listener should continuously try to reconnect if network is lost
	@Test
	public void listenerNetworkLostTest() throws Exception {
	    int delayIndex = 2;
        AtomicInteger handlerExecuted = new AtomicInteger(0);
        listener.setConnectingHandler(ex -> {
            handlerExecuted.incrementAndGet();
        });
        
        listener.openAsync(Duration.ofSeconds(15)).join();
        listener.injectFault(new UpgradeException(CONNECTION_URI, StatusCode.TRY_AGAIN_LATER, "Connection failed due to fault injection."));
        listener.injectFault(new ConnectionLostException());
        assertFalse("listener should be disconnected temporarily for now", listener.isOnline());
        
        Thread.sleep(RelayConstants.CONNECTION_DELAY_INTERVALS[delayIndex - 1].toMillis());
        listener.clearFault();
        
        // ensure we give enough time for the backoff delay then reconnect
        Thread.sleep(RelayConstants.CONNECTION_DELAY_INTERVALS[delayIndex].toMillis() + 500);
        assertTrue("listener should be reconnected now.", listener.isOnline());
        assertTrue("The reconnecting handler should be triggered multiple times. Actual: " + handlerExecuted.get(), handlerExecuted.get() > 1);
	}
	
	@Test
	public void connectMultipleClientsTest() throws URISyntaxException {
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
	}
	
	@Test
	public void closedOrReadonlyResponseTest() throws IOException {
		int statusCode = HttpStatus.ACCEPTED_202;
		String headerKey = "foo";
		String headerVal = "bar";
		
		listener.openAsync(Duration.ofSeconds(15)).join();
		StringBuilder urlBuilder = new StringBuilder(TestUtil.RELAY_NAMESPACE_URI + TestUtil.ENTITY_PATH);
		urlBuilder.replace(0, 5, "https://");
		URL url = new URL(urlBuilder.toString());
		String tokenString = tokenProvider.getTokenAsync(url.toString(), Duration.ofHours(1)).join().getToken();
		
		// Test setting response header after response stream has been closed
		CompletableFuture<Void> modifiedAfterClose = new CompletableFuture<Void>();
		listener.setRequestHandler(context -> {
			RelayedHttpListenerResponse response = context.getResponse();
            response.setStatusCode(statusCode);
			try {
				context.getResponse().close();
				response.getHeaders().put(headerKey, headerVal);
				modifiedAfterClose.complete(null);
			} catch (Exception e) {
				modifiedAfterClose.completeExceptionally(e.getCause());
			}
		});
		
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("ServiceBusAuthorization", tokenString);

		assertEquals("Response did not have the expected response code.", statusCode, conn.getResponseCode());
		assertNull("Response should not have set the header successfully.", conn.getHeaderField(headerKey));
		Assertions.assertCFThrows(IOException.class, modifiedAfterClose);
		
		// Test setting status code after writing to response stream
		CompletableFuture<Void> modifiedStatusAfterWrite = new CompletableFuture<Void>();
		listener.setRequestHandler(context -> {
			RelayedHttpListenerResponse response = context.getResponse();
            
			try {
				response.getHeaders().put(headerKey, headerVal);
				response.getOutputStream().write(0);
				response.setStatusCode(statusCode);
				modifiedStatusAfterWrite.complete(null);
			} catch (Exception e) {
				modifiedStatusAfterWrite.completeExceptionally(e);
			} finally {
				context.getResponse().close();
			}
		});
		
		conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("ServiceBusAuthorization", tokenString);

		assertNotEquals("Response should not have set the status code successfully.", statusCode, conn.getResponseCode());
		assertEquals("Response did not have the expected header field.", headerVal, conn.getHeaderField(headerKey));
		Assertions.assertCFThrows(IllegalStateException.class, modifiedStatusAfterWrite);
		
		// Test setting response header after writing to response stream
		CompletableFuture<Void> modifiedHeaderAfterWrite = new CompletableFuture<Void>();
		listener.setRequestHandler(context -> {
			RelayedHttpListenerResponse response = context.getResponse();
            
			try {
				response.setStatusCode(statusCode);
				response.getOutputStream().write(0);
				response.getHeaders().put(headerKey, headerVal);
				modifiedHeaderAfterWrite.complete(null);
			} catch (Exception e) {
				modifiedHeaderAfterWrite.completeExceptionally(e);
			} finally {
				context.getResponse().close();
			}
		});
		
		conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("ServiceBusAuthorization", tokenString);

		assertEquals("Response did not have the expected status code.", statusCode, conn.getResponseCode());
		assertNull("Response should not have set the header field successfully.", conn.getHeaderField(headerKey));
		Assertions.assertCFThrows(IllegalStateException.class, modifiedHeaderAfterWrite);
	}
}
