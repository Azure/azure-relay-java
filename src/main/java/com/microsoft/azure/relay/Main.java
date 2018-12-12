package com.microsoft.azure.relay;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.*;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.EncodeException;
import javax.websocket.Endpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.json.JSONObject;

public class Main {

	// empty test string for GET method
	private static String emptyStr = "";
	// small test string that's under 64kb
	private static String smallStr = "smallStr";
	// large test string that's over 64kb
	private static String largeStr;
	private static HybridConnectionListener listener;
	
	static final String CONNECTION_STRING_ENV_VARIABLE_NAME = "RELAY_CONNECTION_STRING_ENVIRONMENT_VARIABLE";
	static final Map<String, String> connectionParams = StringUtil.parseConnectionString(System.getenv(CONNECTION_STRING_ENV_VARIABLE_NAME));
	static final String RELAY_NAME_SPACE = connectionParams.get("Endpoint");
	static final String CONNECTION_STRING = connectionParams.get("EntityPath");
	static final String KEY_NAME = connectionParams.get("SharedAccessKeyName");
	static final String KEY = connectionParams.get("SharedAccessKey");
	static int bytes = 0;
	
	public static void main(String[] args) throws Exception {
		StringBuilder builder = new StringBuilder();
		String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
		for (int i = 0; i < 2000; i++) {
			builder.append(alphabet);
		}
		largeStr = builder.toString();
		
		TokenProvider tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(KEY_NAME, KEY);
		HybridConnectionListener listener = new HybridConnectionListener(new URI(RELAY_NAME_SPACE + CONNECTION_STRING), tokenProvider);
		
		listener.setConnectingHandler((o, e) -> System.out.println("Connecting handler"));
        listener.setOfflineHandler((o, e) -> System.out.println("Offline handler"));
        listener.setOnlineHandler((o, e) -> System.out.println("Online handler"));
        
        listener.openAsync().get();
        
		webSocketServer(listener);
		webSocketClient();

//        for (int i = 0; i < 10; i++) {
//            httpGETAndSmallResponse(listener);
//            httpGETAndLargeResponse(listener);
//            httpSmallPOSTAndSmallResponse(listener);
//            httpSmallPOSTAndLargeResponse(listener);
//            httpLargePOSTAndSmallResponse(listener);
//            httpLargePOSTAndLargeResponse(listener);
//        }

		System.out.println("done");
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
		TokenProvider tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(KEY_NAME, KEY);
		StringBuilder urlBuilder = new StringBuilder(RELAY_NAME_SPACE + CONNECTION_STRING);
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
		TokenProvider tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(KEY_NAME, KEY);
		HybridConnectionClient client = new HybridConnectionClient(new URI(RELAY_NAME_SPACE + CONNECTION_STRING), tokenProvider);
		ClientWebSocket webSocket = new ClientWebSocket();
		webSocket.receiveMessageAsync().thenAccept((bytesReceived) -> {
			bytes += bytesReceived.array().length;
			System.out.println("Total Bytes received: " + bytes + ", Sender received: " + new String(bytesReceived.array()));
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
				listener.closeAsync();
			}
		});
	} 
}