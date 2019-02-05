﻿<p align="center">
  <img src="relay.png" alt="Microsoft Azure Relay" width="100"/>
</p>

# Microsoft Azure Relay Hybrid Connections for Java

This library is built using Java 8. Please ensure that you have JDK 1.8+ installed before running this project.

Azure Relay is one of the key capability pillars of the Azure Service Bus
platform. The Relay’s "Hybrid Connections" capability is a secure,
open-protocol evolution based on HTTP and WebSockets. It supersedes the former,
equally named "BizTalk Services" feature that was built on a proprietary
protocol foundation. The integration of Hybrid Connections into Azure App
Services will continue to function as-is.

"Hybrid Connections" allows establishing bi-directional, binary stream
communication between two networked applications, whereby either or both parties
can reside behind NATs or Firewalls. This document describes the client-side
interactions with the Hybrid Connections relay for connecting clients in
listener and sender roles and how listeners accept new connections.

## How to provide feedback

See our [Contribution Guidelines](./.github/CONTRIBUTING.md).

## Samples

For Relay Hybrid Connections samples for Java, see the [azure/azure-relay](https://github.com/Azure/azure-relay/tree/master/samples/hybrid-connections/java) service repository.

### Registering Hybrid Connections 

To use the Hybrid Connection feature, you must first register a Hybrid
Connection path with the Relay service. Hybrid Connection paths are string
expressions that uniquely identify the entity. 

To create Hybrid Connection entities, you first need a Service Bus Relay
namespace that you can create either through the Azure portal, the Azure
PowerShell tools, or the cross-platform Azure CLI. 

Existing Azure Relay namespaces can be managed in the Azure Portal, where you
can also add, edit, or remove Hybrid Connection paths interactively. 

The
following two settings are specific to Hybrid Connections: 

| Property                    | Description                          |
|-----------------------------|--------------------------------------|
| RequiresClientAuthorization | If this is set to false (the default is true), sending clients can connect to a listener through the Relay without providing an authorization token. In this case, the Relay will not enforce any if its ownaccess rules, but the listener can still evaluate the Authorization HTTP header or use some other model for access control. |
| ListenerCount               | This is an informational value that’s available via GetRuntimeInformationAsync and gives the number of connected listeners on this Hybrid Connection as the value is queried. |

Up to 25 listeners can be concurrently connected and the Relay will distribute
incoming connection requests across all connected listeners, equivalent to a
network load balancer.

### Handling Tokens

Creating a listener requires an access token that confers the "Listen" right on
the Hybrid Connection entity or at the namespace level. Creating a sender
connection requires, unless the Hybrid Connection entity is configured
otherwise, a token that confers the "Send" right. The follows the [shared access signature authentication model](https://azure.microsoft.com/documentation/articles/service-bus-shared-access-signature-authentication/)
that is common across all Service Bus capabilities and entities.

Access tokens are created from an Authorization rule and key using a token
provider helper as described in the article linked above; the Hybrid Connections
API has its own `TokenProvider` class, however. The `TokenProvider` can
be initialized from a rule and key with
`TokenProvider.CreateSharedAccessSignatureTokenProvider(ruleName, key)` or
it can be initialized from an existing token string that has been issued by some
other application with `TokenProvider.CreateSharedAccessSignatureTokenProvider(token)`.

The initialized `TokenProvider` instance is used by the `HybridConnectionListener`
and `HybridConnectionClient` API to create tokens as needed. 

However, with Hybrid Connections even more than with other Service Bus features,
you may have scenarios where you will want the Relay to protect your endpoint,
but you also don’t want to hand the SAS rule and key to the client outright. One
such case are browser-based clients. For a browser-based client that needs to
connect to a resource made available via a relayed WebSocket, the server-side
web site can hold on to the required SAS rule and key, and use the `TokenProvider`
to create a short-lived token string and pass that on to the client: 

```java
TokenProvider tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(SharedAccessKeyName, SharedAccessKey());
String tokenString = tokenProvider.getTokenAsync("http://namespace.servicebus.windows.net/path", Duration.ofSeconds(30)).join().getToken();
```

The token created in the exemplary snippet above will only be valid to establish
a connection within 30 seconds of receiving it.

### Creating Listeners 

The Hybrid Connection API follows a very common networking design pattern. There
is a listener object that is first opened to allow incoming connections to flow
and from which the application can then accept these incoming connections for
handling.

With the listener object successfully instantiated, it should connect to the Azure Relay cloud service and listen for any incoming connections using `listener.openAsync(timeout)`.
After having connecting to the Azure Relay cloud service, the listener will be able to accept HTTP or web socket connections, then receive and send messages from these connections.
The example illustrates some of the typical usages of the listener object, and more detailed samples may be found here: [Azure Relay Java Examples](https://github.com/Azure/azure-relay/tree/master/samples/hybrid-connections/java).

```java
HybridConnectionListener listener = new HybridConnectionListener("sb://namespace.servicebus.windows.net/path", tokenProvider); 
listener.openAsync(Duration.ofSeconds(60)).join(); 

listener.setRequestHandler((context) -> {
	// Handle HTTP requests in this handler

	RelayedHttpListenerResponse response = context.getResponse();
	response.setStatusCode(202);
	response.setStatusDescription("OK");
	resonse.close();
});

do 
{
	// This accepts connections with remote web sockets and provides a connected web socket instance
	ClientWebSocket websocket = listener.acceptConnectionAsync().join(); 
	
	// Send and receive messages with the connected websocket object
} 
while( … );
```

The ```HybridConnectionListener``` will
aggressively attempt to stay connected once opened. Should the local network
connection drop or connectivity to the Relay become interrupted, the listener
will patiently retry until the listener can be restored. 

`ClientWebSocket` is a web socket implementation based on javax.websocket from the Jetty library,
and it will support many asynchronous operations such as `sendAsync()`, `receiveMessageAsync()`
and `closeAsync()`. The usage of `CompletableFuture` within the implementation provides many useful
hooks to insert handlers for different events.

### Creating Clients 

Client connections are created using the `HybridConnectionClient` class.
There are two variants of the constructor: one takes the target address and a
`TokenProvider` that can produce a "Send" token for the target. The other
omits the token provider for use with Hybrid Connections that are set up without
client authorization.

New web socket connections are created via the `createConnectionAsync()` method. When
the connection has been established, the CompletableFuture completes with a
`ClientWebSocket` that is connected to the remote listener. If the
connection attempt fails, a `RuntimeException` will be raised that indicates the
reason for why the connection could not be established.

## How do I run the unit tests? 

The unit tests are written using JUnit. You can simply run the unit tests from within an IDE such as Eclipse or Intellij by using their respective interfaces. 

If running from within command prompt, simply navigate to the root folder of the project (`/azure-relay-java`) and run `mvn clean test`.
