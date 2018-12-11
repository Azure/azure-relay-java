// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.net.URI;
import java.net.URISyntaxException;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.CompletionException;

import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;


public class HybridConnectionClient {
	static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(70);
	static final boolean IS_DEBUG = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString().indexOf("-agentlib:jdwp") > 0;

	// Gets the address of this HybridConnection to connect through. The address on which to listen for HybridConnections.
	// This address should be of the format "sb://contoso.servicebus.windows.net/yourhybridconnection".
	private URI address;
	// Gets or sets proxy information for connecting to ServiceBus.
	private Proxy proxy;
	// Gets the TokenProvider for authenticating HybridConnections.
	private TokenProvider tokenProvider;
	// Gets or sets the timeout used when connecting a HybridConnection. Default value is 70 seconds.
	private Duration operationTimeout;
	// Controls whether the ClientWebSocket from .NET Core or a custom implementation is used.
	private boolean useBuiltInClientWebSocket;
	// Gets or sets the connection buffer size. Default value is 64K.
	private int connectionBufferSize;
	
	public URI getAddress() {
		return address;
	}
	
	public Proxy getProxy() {
		return proxy;
	}
	public void setProxy(Proxy proxy) {
		this.proxy = proxy;
	}
	
	public TokenProvider getTokenProvider() {
		return tokenProvider;
	}

	public Duration getOperationTimeout() {
		return operationTimeout;
	}
	public void setOperationTimeout(Duration operationTimeout) {
		this.operationTimeout = operationTimeout;
	}

	public boolean isUseBuiltInClientWebSocket() {
		return useBuiltInClientWebSocket;
	}
	public void setUseBuiltInClientWebSocket(boolean useBuiltInClientWebSocket) {
		this.useBuiltInClientWebSocket = useBuiltInClientWebSocket;
	}

	/// <summary>
	/// Create a new HybridConnectionClient instance for initiating
	/// HybridConnections where no client authentication is required.
	/// </summary>
	/// <param name="address">The address on which to listen for HybridConnections.
	/// This address should
	/// be of the format
	/// "sb://contoso.servicebus.windows.net/yourhybridconnection".</param>
	public HybridConnectionClient(URI address) {
		this.initialize(address, DEFAULT_CONNECTION_TIMEOUT, null, false);
	}

	/// <summary>
	/// Create a new HybridConnectionClient instance for initiating
	/// HybridConnections with client authentication.
	/// </summary>
	/// <param name="address">The address on which to listen for HybridConnections.
	/// This address should
	/// be of the format
	/// "sb://contoso.servicebus.windows.net/yourhybridconnection".</param>
	/// <param name="tokenProvider">The TokenProvider for connecting to
	/// ServiceBus.</param>
	public HybridConnectionClient(URI address, TokenProvider tokenProvider) {
		this.initialize(address, DEFAULT_CONNECTION_TIMEOUT, tokenProvider, true);
	}

	/// <summary>Creates a new instance of <see cref="HybridConnectionClient" />
	/// using the specified connection String.</summary>
	/// <param name="connectionString">The connection String to use. This connection
	/// String must include the EntityPath property.</param>
	/// <returns>The newly created <see cref="HybridConnectionClient" />
	/// instance.</returns>
	/// <exception cref="System.ArgumentException">Thrown when the format of the
	/// <paramref name="connectionString" /> parameter is incorrect.</exception>
	public HybridConnectionClient(String connectionString) {
     	this(connectionString, null, true);
     }

	/// <summary>Creates a new instance of <see cref="HybridConnectionClient" />
	/// from a connection String and the specified HybridConection path. Use this overload only when the connection String does not use the
	/// <see cref="RelayConnectionStringBuilder.EntityPath" /> property.</summary>
	/// <param name="connectionString">The connection String used. This connection
	/// String must not include the EntityPath property.</param>
	/// <param name="path">The path to the HybridConnection.</param>
	/// <returns>The created <see cref="HybridConnectionClient" />.</returns>
	/// <exception cref="System.ArgumentException">Thrown when the format of the
	/// <paramref name="connectionString" /> parameter is incorrect.</exception>
	public HybridConnectionClient(String connectionString, String path) {
		this(connectionString, path, false);
     }

	// This private .ctor handles both of the public overloads which take connectionString
	HybridConnectionClient(String connectionString, String path, boolean pathFromConnectionString) {
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
			// Only change from our default (70 seconds) if it appears user has changed the
			// OperationTimeout in the connectionString.
			connectTimeout = builder.getOperationTimeout();
		}

		try {
			this.initialize(
				new URI(builder.getEndpoint().toString() + builder.getEntityPath()), 
				connectTimeout, 
				tokenProvider,
				tokenProvider != null
			);
		} catch (URISyntaxException e) {
			// TODO: trace
			throw new IllegalArgumentException("invalid uri.");
		}
	}
	
	/// <summary>
	/// Establishes a new send-side HybridConnection and returns the Stream.
	/// </summary>
	public CompletableFuture<ClientWebSocket> createConnectionAsync(ClientWebSocket webSocket) {
		// TODO: trace
        TrackingContext trackingContext = createTrackingContext(this.address);
//         String traceSource = nameof(HybridConnectionClient) + "(" + trackingContext + ")";
//         TimeoutHelper timeoutHelper = new TimeoutHelper(this.operationTimeout);

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
		    	future = CompletableFutureUtil.timedSupplyAsync(null, () -> {
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
				        	newSocket.connectAsync(uri).get();
				        	return newSocket;
				        } else {
				        	webSocket.connectAsync(uri).get();
				        }
					} catch (URISyntaxException | InterruptedException | ExecutionException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					return webSocket;
				});
			} catch (CompletionException e) {
				// should not be exception here since timeout is null
				e.printStackTrace();
			}
		    return future;
		} else {
			throw new IllegalArgumentException("tokenProvider cannot be null.");
		}
	}

	public void close() {
		CompletableFutureUtil.cleanup();
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

	// TODO
	protected static TrackingContext createTrackingContext(URI address) {
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

	void initialize(URI address, Duration operationTimeout, TokenProvider tokenProvider,
			boolean tokenProviderRequired) {
		if (address == null) {
			// TODO: trace
//             throw RelayEventSource.Log.ArgumentNull(nameof(address), this);
			throw new IllegalArgumentException("cannot initiate hybrid connection client with null uri");
		} else if (!address.getScheme().equals(RelayConstants.HYBRID_CONNECTION_SCHEME)) {
//			throw RelayEventSource.Log.Argument(nameof(address),
//					SR.GetString(SR.InvalidUriScheme, address.Scheme, RelayConstants.HybridConnectionScheme), this);
			throw new IllegalArgumentException("cannot initiate hybrid connection client with invalid uri scheme");
		} else if (tokenProviderRequired && tokenProvider == null) {
//			throw RelayEventSource.Log.ArgumentNull(nameof(tokenProvider), this);
			throw new IllegalArgumentException("cannot initiate hybrid connection client with null token provider");
		}

		this.address = address;
		this.tokenProvider = tokenProvider;
		this.connectionBufferSize = RelayConstants.DEFAULT_CONNECTION_BUFFER_SIZE;
		this.operationTimeout = operationTimeout;
//		this.proxy = WebRequest.DefaultWebProxy;
		this.useBuiltInClientWebSocket = HybridConnectionConstants.DEFAULT_USE_BUILTIN_CLIENT_WEBSOCKET;
	}
}
