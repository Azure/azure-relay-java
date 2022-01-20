// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.time.Instant;

public class SecurityToken {
	private final String token;
	private Instant expiresAtUtc;
	private String audience;

	SecurityToken(String tokenString) { 
		this(tokenString, null, null);
	}
	
	SecurityToken(String tokenString, Instant expiresAtUtc, String audience) {
		if (StringUtil.isNullOrEmpty(tokenString)) {
			throw new IllegalArgumentException("Cannot create a SecurityToken with a null or empty token string");
		}
		
		this.token = tokenString;
		this.expiresAtUtc = expiresAtUtc;
		this.audience = audience;
	}
	
	/**
	 * @return Returns the token string from this token
	 */
	public String getToken() {
		return token;
	}

	/**
	 * @return Returns the expiry time for this token
	 */
	public Instant getExpiresAtUtc() {
		return expiresAtUtc;
	}
	
	protected void setExiresAtUtc(Instant expiresAt) {
		this.expiresAtUtc = expiresAt;
	}

	/**
	 * @return Returns the audience URL string for this token.
	 */
	public String getAudience() {
		return audience;
	}
	
	protected void setAudience(String audience) {
		this.audience = audience;
	}
}
