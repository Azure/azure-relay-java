// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.net.URI;
import java.net.URISyntaxException;

public abstract class TokenProvider {
	static final Duration DEFAULT_TOKEN_TIMEOUT = Duration.ofMinutes(60);

	// Initializes a new instance of the <see cref="TokenProvider" /> class.
	protected TokenProvider() {
	}

	/**
	 * Construct a TokenProvider based on the provided Key Name and Shared Access
	 * Key.
	 * 
	 * @param sharedAccessSignature The signature associated with the
	 *                              SharedAccessKeyAuthorizationRule
	 * @return A TokenProvider initialized with the provided RuleId and Password
	 */
	public static TokenProvider createSharedAccessSignatureTokenProvider(String sharedAccessSignature) {
		return new SharedAccessSignatureTokenProvider(sharedAccessSignature);
	}

	/**
	 * Construct a TokenProvider based on the provided Key Name and Shared Access
	 * Key.
	 * 
	 * @param keyName         The key name of the corresponding
	 *                        SharedAccessKeyAuthorizationRule.
	 * @param sharedAccessKey The key associated with the
	 *                        SharedAccessKeyAuthorizationRule
	 * @return A TokenProvider instance used for authentication
	 */
	public static TokenProvider createSharedAccessSignatureTokenProvider(String keyName, String sharedAccessKey) {
		return new SharedAccessSignatureTokenProvider(keyName, sharedAccessKey);
	}

	/**
	 * Gets a SecurityToken for the given audience and duration
	 * 
	 * @param urlString The target audience for the security token
	 * @param validFor  How long the generated token should be valid for
	 * @return Returns a CompletableFuture of the SecutiryToken that completes once
	 *         generated
	 */
	public CompletableFuture<SecurityToken> getTokenAsync(String urlString, Duration validFor) {
		if (StringUtil.isNullOrEmpty(urlString)) {
			// TODO: trace
//            throw RelayEventSource.Log.ArgumentNull(nameof(audience), this);
		}

		TimeoutHelper.throwIfNegativeArgument(validFor, "validFor");
		urlString = normalizeAudience(urlString);
		return this.onGetTokenAsync(urlString, validFor);
	}

	/**
	 * Implemented by derived TokenProvider types to generate their SecurityTokens.
	 * 
	 * @param urlString The target audience for the security token
	 * @param validFor  How long the generated token should be valid for
	 * @return Returns a CompletableFuture of the SecutiryToken that completes once
	 *         generated
	 */
	protected abstract CompletableFuture<SecurityToken> onGetTokenAsync(String urlString, Duration validFor);

	static String normalizeAudience(String audience) {
		try {
			String audienceURIString = new URI(audience).normalize().toString();
			return (audienceURIString.charAt(audienceURIString.length() - 1) == '/') ? audienceURIString + '/'
					: audienceURIString;
		} catch (URISyntaxException e) {
			// urlString should alredy be checked, so exception here is unlikely
			return null;
		}
	}
}
