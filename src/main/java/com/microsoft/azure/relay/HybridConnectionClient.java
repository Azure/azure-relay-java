// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionException;

public class HybridConnectionClient {
	static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(70);
	static final boolean IS_DEBUG = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString().indexOf("-agentlib:jdwp") > 0;

	/**
	 * The address on which this HybridConnection will connect to. This address should be of the format "sb://contoso.servicebus.windows.net/yourhybridconnection".
	 */
	private URI address;
	
	// Gets or sets proxy information for connecting to ServiceBus.
//	private Proxy proxy;
	
	/**
	 * The TokenProvider for authenticating this HybridConnection listener.
	 */
	private TokenProvider tokenProvider;
	
	/**
	 * The default timeout for connecting a HybridConnection. Default value is 70 seconds.
	 */
	private Duration operationTimeout;
	
	public URI getAddress() {
		return address;
	}
	
//	public Proxy getProxy() {
//		return proxy;
//	}
//	public void setProxy(Proxy proxy) {
//		this.proxy = proxy;
//	}
	
	/**
	 * @return Get the TokenProvider for authenticating this HybridConnection listener.
	 */
	public TokenProvider getTokenProvider() {
		return tokenProvider;
	}

	/**
	 * @return The default timeout for connecting a HybridConnection. Default value is 70 seconds.
	 */
	public Duration getOperationTimeout() {
		return operationTimeout;
	}
	
	public void setOperationTimeout(Duration operationTimeout) {
		this.operationTimeout = operationTimeout;
	}

	/**
	 * Create a new HybridConnectionClient instance for initiating HybridConnections where no client authentication is required.
	 * @param address The address on which to listen for HybridConnections. This address should be of the format "sb://contoso.servicebus.windows.net/yourhybridconnection".
	 */
	public HybridConnectionClient(URI address) {
		this.initialize(address, DEFAULT_CONNECTION_TIMEOUT, null, false);
	}

	/**
	 * Create a new HybridConnectionClient instance for initiating HybridConnections with client authentication.
	 * @param address The address on which to listen for HybridConnections. This address should be of the format "sb://contoso.servicebus.windows.net/yourhybridconnection".
	 * @param tokenProvider The TokenProvider for authenticating this HybridConnection client.
	 */
	public HybridConnectionClient(URI address, TokenProvider tokenProvider) {
		this.initialize(address, DEFAULT_CONNECTION_TIMEOUT, tokenProvider, true);
	}

	/**
	 * Create a new HybridConnectionClient instance for initiating HybridConnections.
	 * @param connectionString The connection string to use. This connection string must include the EntityPath property.
	 * @throws URISyntaxException Thrown when the format of the connectionSring is incorrect
	 */
	public HybridConnectionClient(String connectionString) throws URISyntaxException {
     	this(connectionString, null, true);
     }

	/**
	 * Create a new HybridConnectionListener instance for initiating HybridConnections.
	 * @param connectionString The connection string to use. This connection string must include the EntityPath property.
	 * @param path The path to the HybridConnection.
	 * @throws URISyntaxException Thrown when the format of the connectionSring is incorrect
	 */
	public HybridConnectionClient(String connectionString, String path) throws URISyntaxException {
		this(connectionString, path, false);
     }

	/**
	 * This private .ctor handles both of the public overloads which take connectionString
	 * @param connectionString The connection String used. This connection string must not include the EntityPath property.
	 * @param path path The path to the HybridConnection.
	 * @param pathFromConnectionString True if path is implicitly defined in the connection string
	 * @throws URISyntaxException Thrown when the format of the connectionSring is incorrect
	 */
	HybridConnectionClient(String connectionString, String path, boolean pathFromConnectionString) throws URISyntaxException {
		if (StringUtil.isNullOrWhiteSpace(connectionString)) {
			throw new IllegalArgumentException("the connection string cannot be null.");
		}

		RelayConnectionStringBuilder builder = new RelayConnectionStringBuilder(connectionString);
		builder.validate();

		if (pathFromConnectionString) {
			if (StringUtil.isNullOrWhiteSpace(builder.getEntityPath())) {
				// EntityPath is required in connectionString.
				throw new IllegalArgumentException("entityPath is required in connectionString");
			}
		} else {
			
			if (StringUtil.isNullOrWhiteSpace(path)) {
				throw new IllegalArgumentException("path is required outside of connectionString");
			} else if (!StringUtil.isNullOrWhiteSpace(builder.getEntityPath())) {
				throw new IllegalArgumentException("connectionString is not allowed to include EntityPath");
			}
			builder.setEntityPath(path);
		}

		TokenProvider tokenProvider = null;
		if (!StringUtil.isNullOrEmpty(builder.getSharedAccessSignature())
				|| !StringUtil.isNullOrEmpty(builder.getSharedAccessKeyName())) {
			tokenProvider = builder.createTokenProvider();
		}

		Duration connectTimeout = DEFAULT_CONNECTION_TIMEOUT;
		if (builder.getOperationTimeout() != RelayConstants.DEFAULT_OPERATION_TIMEOUT) {
			// Only change from our default (70 seconds) if it appears user has changed the operationTimeout in the connectionString.
			connectTimeout = builder.getOperationTimeout();
		}

		this.initialize(new URI(builder.getEndpoint().toString() + builder.getEntityPath()), connectTimeout, tokenProvider, tokenProvider != null);
	}
	
	/**
	 * Establishes a new send-side HybridConnection and returns the websocket with established connections.
	 * @return A CompletableFuture which returns the ClientWebSocket instance when its connection established with the remote endpoint
	 */
	public CompletableFuture<ClientWebSocket> createConnectionAsync() {
		return this.createConnectionAsync(null);
	}
	
	/**
	 * Establishes a new send-side HybridConnection and returns the websocket with established connections.
	 * @param webSocket A user created ClientWebSocket instance which may have predefined handlers
	 * @return A CompletableFuture which returns the ClientWebSocket instance when its connection established with the remote endpoint
	 */
	public CompletableFuture<ClientWebSocket> createConnectionAsync(ClientWebSocket webSocket) {
		// TODO: trace
        TrackingContext trackingContext = createTrackingContext(this.address);
//         String traceSource = nameof(HybridConnectionClient) + "(" + trackingContext + ")";

      // TODO: trace
//         RelayEventSource.Log.ObjectConnecting(traceSource, trackingContext); 
        
		if (this.tokenProvider != null) {
			String audience = HybridConnectionUtil.getAudience(this.address);
			CompletableFuture<SecurityToken> token = this.tokenProvider.getTokenAsync(audience, TokenProvider.DEFAULT_TOKEN_TIMEOUT);
			
			Map<String, List<String>> headers = new HashMap<String, List<String>>();
			try {
				headers.put(RelayConstants.SERVICEBUS_AUTHORIZATION_HEADER_NAME, Arrays.asList(token.get().getToken()));
			} catch (InterruptedException | ExecutionException tokenError) {
				tokenError.printStackTrace();
			}
			HybridConnectionEndpointConfigurator.setHeaders(headers);
		    
			CompletableFuture<ClientWebSocket> future = new CompletableFuture<ClientWebSocket>();
		    try {
				URI uri = HybridConnectionUtil.BuildUri(
					    this.address.getHost(),
					    this.address.getPort(),
					    this.address.getPath(),
					    this.address.getQuery(),
					    HybridConnectionConstants.Actions.CONNECT,
					    trackingContext.getTrackingId()
					);
		        if (webSocket == null) {
		        	ClientWebSocket newSocket = new ClientWebSocket();
		        	future = newSocket.connectAsync(uri, this.operationTimeout).thenApply(result -> newSocket);
		        } else {
		        	future = webSocket.connectAsync(uri, this.operationTimeout).thenApply(result -> webSocket);
		        }
			} catch (CompletionException | URISyntaxException e) {
				e.printStackTrace();
			}
		    return future;
		} else {
			throw new IllegalArgumentException("tokenProvider cannot be null.");
		}
	}
	
	/// <summary>
	/// Gets the <see cref="HybridConnectionRuntimeInformation"/> for this
	/// HybridConnection entity using the default timeout.
	/// Unless specified in the connection String the default is 1 minute.
	/// </summary>
	// TODO
//     public async Task<HybridConnectionRuntimeInformation> GetRuntimeInformationAsync()
//     {
//         using (var cancelSource = new CancellationTokenSource(this.OperationTimeout))
//         {
//             return await this.GetRuntimeInformationAsync(cancelSource.Token).ConfigureAwait(false);
//         }
//     }

	/// <summary>
	/// Gets the <see cref="HybridConnectionRuntimeInformation"/> for this
	/// HybridConnection entity using the provided CancellationToken.
	/// </summary>
	/// <param name="cancellationToken">A cancellation token to observe.</param>
	// TODO
//     public Task<HybridConnectionRuntimeInformation> GetRuntimeInformationAsync(CancellationToken cancellationToken)
//     {
//         if (this.TokenProvider == null)
//         {
//             throw RelayEventSource.Log.ThrowingException(new InvalidOperationException(SR.TokenProviderRequired), this);
//         }
//
//         return ManagementOperations.GetAsync<HybridConnectionRuntimeInformation>(this.Address, this.TokenProvider, cancellationToken);
//     }

	private static TrackingContext createTrackingContext(URI address) {
		if (IS_DEBUG) {
			// In DEBUG builds allow setting the trackingId via query String: "?id=00000000-0000-0000-0000-000000000000"
			String query = address.getQuery();
			if (!StringUtil.isNullOrEmpty(query)) {
				if (query.charAt(0) == '?') {
					query = query.substring(1);
				}
				String[] kvps = query.split("&");
				for (String kvp : kvps) {
					if (kvp.startsWith("id=")) {
						return TrackingContext.create(kvp.substring(3), address);
					}
				}
			}
		}

		return TrackingContext.create(address);
	}

	private void initialize(URI address, Duration operationTimeout, TokenProvider tokenProvider, boolean tokenProviderRequired) {
		
		if (address == null) {
			// TODO: trace
//             throw RelayEventSource.Log.ArgumentNull(nameof(address), this);
			throw new IllegalArgumentException("cannot initiate hybrid connection client with null uri");
			
		} else if (!address.getScheme().equals(RelayConstants.HYBRID_CONNECTION_SCHEME)) {
//			throw RelayEventSource.Log.Argument(nameof(address), SR.GetString(SR.InvalidUriScheme, address.Scheme, RelayConstants.HybridConnectionScheme), this);
			throw new IllegalArgumentException("cannot initiate hybrid connection client with invalid uri scheme");
			
		} else if (tokenProviderRequired && tokenProvider == null) {
//			throw RelayEventSource.Log.ArgumentNull(nameof(tokenProvider), this);
			throw new IllegalArgumentException("cannot initiate hybrid connection client with null token provider");
		}

		this.address = address;
		this.tokenProvider = tokenProvider;
		this.operationTimeout = operationTimeout;
//		this.proxy = WebRequest.DefaultWebProxy;
	}
}
