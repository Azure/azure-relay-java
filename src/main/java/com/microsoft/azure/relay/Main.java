package com.microsoft.azure.relay;

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

public class Main {

	// empty test string for GET method
	private static String emptyStr = "";
	// small test string that's under 64kb
	private static String smallStr = "smallStr";
	// large test string that's over 64kb
	private static String largeStr;
	
	static final String CONNECTION_STRING_ENV_VARIABLE_NAME = "RELAY_CONNECTION_STRING";
	static final RelayConnectionStringBuilder connectionParams = new RelayConnectionStringBuilder(System.getenv(CONNECTION_STRING_ENV_VARIABLE_NAME));
	static final TokenProvider tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(connectionParams.getSharedAccessKeyName(), connectionParams.getSharedAccessKey());
	static int bytes = 0;
	
	public static void main(String[] args) throws Exception {
		long startTime = System.currentTimeMillis();
		StringBuilder builder = new StringBuilder();
		String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
		for (int i = 0; i < 2000; i++) {
			builder.append(alphabet);
		}
		largeStr = builder.toString();

		HybridConnectionListener listener = new HybridConnectionListener(new URI(connectionParams.getEndpoint()+ connectionParams.getEntityPath()), tokenProvider);
        
        listener.openAsync().get();
        
		webSocketServer(listener);
		webSocketClient();

        for (int i = 0; i < 1; i++) {
            httpGETAndSmallResponse(listener);
            httpGETAndLargeResponse(listener);
            httpSmallPOSTAndSmallResponse(listener);
            httpSmallPOSTAndLargeResponse(listener);
            httpLargePOSTAndSmallResponse(listener);
            httpLargePOSTAndLargeResponse(listener);
        }

		listener.closeAsync().join();
		
		long endTime = System.currentTimeMillis();
		System.out.println("Execution used " + ((endTime - startTime)) + " ms.");
	}
	
	private static void httpGETAndSmallResponse(HybridConnectionListener listener) throws IOException, InterruptedException, ExecutionException {
		System.out.println("-----httpGETAndSmallResponse-----");
		listener.setRequestHandler((context) -> requestHandler(context, emptyStr, smallStr));
        requestSender("GET", smallStr, emptyStr);
	}
	
	private static void httpGETAndLargeResponse(HybridConnectionListener listener) throws IOException, InterruptedException, ExecutionException {
		System.out.println("-----httpGETAndLargeResponse-----");
		listener.setRequestHandler((context) -> requestHandler(context, emptyStr, largeStr));
        requestSender("GET", largeStr, emptyStr);
	}
	
	private static void httpSmallPOSTAndSmallResponse(HybridConnectionListener listener) throws IOException, InterruptedException, ExecutionException {
		System.out.println("-----httpSmallPOSTAndSmallResponse-----");
		listener.setRequestHandler((context) -> requestHandler(context, smallStr, smallStr));
        requestSender("POST", smallStr, smallStr);
	}
	
	private static void httpSmallPOSTAndLargeResponse(HybridConnectionListener listener) throws IOException, InterruptedException, ExecutionException {
		System.out.println("-----httpSmallPOSTAndLargeResponse-----");
		listener.setRequestHandler((context) -> requestHandler(context, smallStr, largeStr));
        requestSender("POST", largeStr, smallStr);
	}
	
	private static void httpLargePOSTAndSmallResponse(HybridConnectionListener listener) throws IOException, InterruptedException, ExecutionException {
		System.out.println("-----httpLargePOSTAndSmallResponse-----");
		listener.setRequestHandler((context) -> requestHandler(context, largeStr, smallStr));
        requestSender("POST", smallStr, largeStr);
	}
	
	private static void httpLargePOSTAndLargeResponse(HybridConnectionListener listener) throws IOException, InterruptedException, ExecutionException {
		System.out.println("-----httpLargePOSTAndLargeResponse-----");
        listener.setRequestHandler((context) -> requestHandler(context, largeStr, largeStr));
        requestSender("POST", largeStr, largeStr);
	}
	
	private static void requestHandler(RelayedHttpListenerContext context, String msgExpected, String msgToSend) {
            // Do something with context.Request.Url, HttpMethod, Headers, InputStream...
            RelayedHttpListenerResponse response = context.getResponse();
            response.setStatusCode(HttpStatus.ACCEPTED_202);
            response.setStatusDescription("OK");
            
            String receivedText = (context.getRequest().getInputStream() != null) ? new String(context.getRequest().getInputStream().array()) : "";
            
            boolean receivedExpected = receivedText.equals(msgExpected);
            System.out.println("requestHandler received expected: " + receivedExpected);
            if (!receivedExpected) {
            	System.out.println("received: " + receivedText.length() + ", expected: " + msgExpected.length());
            }
            
            try {
				response.getOutputStream().write((msgToSend).getBytes());
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

            // The context MUST be closed here
            context.getResponse().close();
	}
	
	private static void requestSender(String method, String msgExpected, String msgToSend) throws IOException, InterruptedException, ExecutionException {
		URL url = new URL(connectionParams.getHttpUrlString());
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
		
		System.out.println(conn.getResponseCode());
		while ((inputLine = inStream.readLine()) != null) {
			builder.append(inputLine);
		}
		inStream.close();

        boolean receivedExpected = builder.toString().equals(msgExpected);
        System.out.println("requestSender received expected: " + receivedExpected);
        if (!receivedExpected) {
        	System.out.println("received: " + builder.toString().length() + ", expected: " + msgExpected.length());
        }
	}
	
	// sends a message to the server through websocket
	private static void webSocketClient() throws URISyntaxException, InterruptedException, ExecutionException, IOException {
		HybridConnectionClient client = new HybridConnectionClient(new URI(connectionParams.getEndpoint()+ connectionParams.getEntityPath()), tokenProvider);
		ClientWebSocket webSocket = new ClientWebSocket();
		webSocket.receiveMessageAsync().thenAccept((bytesReceived) -> {
			bytes += bytesReceived.array().length;
			System.out.println("Total Bytes received: " + bytes + ", Sender received: " + new String(bytesReceived.array()));
			webSocket.closeAsync().join();
		});
		
		client.createConnectionAsync(webSocket).get();
		webSocket.sendAsync("hello world");
	}
	
	private static void webSocketServer(HybridConnectionListener listener) throws URISyntaxException, InterruptedException, ExecutionException {
		CompletableFuture<ClientWebSocket> conn = listener.acceptConnectionAsync();
		conn.thenAcceptAsync((websocket) -> {
			while (true) {
				ByteBuffer bytesReceived = websocket.receiveMessageAsync().join();
				String msg = new String(bytesReceived.array());
				System.out.println("Listener Received: " + msg);
				websocket.sendAsync(msg);
//				listener.closeAsync();
			}
		});
	} 
}