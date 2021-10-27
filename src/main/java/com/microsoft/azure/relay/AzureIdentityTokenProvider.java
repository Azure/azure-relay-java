package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;

class AzureIdentityTokenProvider extends TokenProvider {
	static final String AAD_RELAY_AUDIENCE = "https://relay.azure.net/";
	private final TokenRequestContext tokenRequestContext;
	private final TokenCredential tokenCredential;
	
	AzureIdentityTokenProvider(TokenCredential tokenCredential) {
		if (tokenCredential == null) {
			throw new IllegalArgumentException("The TokenCredential provided must not be null.");
		}
		
		this.tokenCredential = tokenCredential;
		this.tokenRequestContext = new TokenRequestContext();
		this.tokenRequestContext.addScopes(String.format("%s/.default", AAD_RELAY_AUDIENCE));
	}
	
	// Note that the validFor provided by the user has no effect, the result value is whatever the returned AccessToken has.
	@Override
	protected CompletableFuture<SecurityToken> onGetTokenAsync(String urlString, Duration validFor) {
		return this.tokenCredential.getToken(tokenRequestContext).toFuture().thenApply(accessToken -> {
			return new SecurityToken(accessToken.getToken(), accessToken.getExpiresAt().toInstant(), urlString);
		});
	}
}
