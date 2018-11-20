// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.net.URI;
import java.net.URISyntaxException;

public abstract class TokenProvider
{
    static final Duration DEFAULT_TOKEN_TIMEOUT = Duration.ofMinutes(60);
    
    // The synchronization object for the given instance.
    private Object thisLock;

    // Initializes a new instance of the <see cref="TokenProvider" /> class.
    protected TokenProvider()
    {
        this.thisLock = new Object();
    }

    // Construct a TokenProvider based on a sharedAccessSignature.
    // <param name="sharedAccessSignature">The signature associated with the SharedAccessKeyAuthorizationRule</param>
    // <returns>A TokenProvider initialized with the provided RuleId and Password</returns>
    public static TokenProvider createSharedAccessSignatureTokenProvider(String sharedAccessSignature)
    {
        return new SharedAccessSignatureTokenProvider(sharedAccessSignature);
    }

    // Construct a TokenProvider based on the provided Key Name and Shared Access Key.
    // <param name="keyName">The key name of the corresponding SharedAccessKeyAuthorizationRule.</param>
    // <param name="sharedAccessKey">The key associated with the SharedAccessKeyAuthorizationRule</param>
    // <returns>A TokenProvider initialized with the provided RuleId and Password</returns>
    public static TokenProvider createSharedAccessSignatureTokenProvider(String keyName, String sharedAccessKey)
    {
        return new SharedAccessSignatureTokenProvider(keyName, sharedAccessKey);
    }
    
    protected Object getThisLock() {
    	return this.thisLock;
    }

    // Gets a <see cref="SecurityToken"/> for the given audience and duration.
    // <param name="audience">The target audience for the security token.</param>
    // <param name="validFor">How long the generated token should be valid for.</param>
    // <returns>A Task returning the newly created SecurityToken.</returns>
    public CompletableFuture<SecurityToken> getTokenAsync(String audience, Duration validFor)
    {
        if (StringUtil.isNullOrEmpty(audience))
        {
        	// TODO: trace
//            throw RelayEventSource.Log.ArgumentNull(nameof(audience), this);
        }

//        TimeoutHelper.ThrowIfNegativeArgument(validFor, nameof(validFor));
        audience = NormalizeAudience(audience);
        return this.onGetTokenAsync(audience, validFor);
    }

    // Implemented by derived TokenProvider types to generate their SecurityTokens.
    // <param name="audience">The target audience for the security token.</param>
    // <param name="validFor">How long the generated token should be valid for.</param>
    protected abstract CompletableFuture<SecurityToken> onGetTokenAsync(String audience, Duration validFor);

    static String NormalizeAudience(String audience)
    {
    	try {
    		String audienceURIString = new URI(audience).normalize().toString();
    		return (audienceURIString.charAt(audienceURIString.length() - 1) == '/') ? audienceURIString + '/' : audienceURIString;
    	} catch (URISyntaxException e) {
    		// TODO: trace
    		return null;
    	}
    }
}
