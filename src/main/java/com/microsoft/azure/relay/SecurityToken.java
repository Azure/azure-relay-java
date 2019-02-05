// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.util.HashMap;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public class SecurityToken {
	private final String token;
	private final String audienceFieldName;
	private final String expiresOnFieldName;
	private final String keyValueSeparator;
	private final String pairSeparator;
	private Instant expiresAtUtc;
	private String audience;

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

	/**
	 * @return Returns the audience URL string for this token
	 */
	public String getAudience() {
		return audience;
	}

	/**
	 * Creates a new instance of the SecurityToken class.
	 * 
	 * @param tokenString        A token in String format.
	 * @param audienceFieldName  The key name for the audience field.
	 * @param expiresOnFieldName The key name for the expires on field.
	 * @param keyValueSeparator  The separator between keys and values.
	 * @param pairSeparator      The separator between different key/value pairs.
	 */
	protected SecurityToken(String tokenString, String audienceFieldName, String expiresOnFieldName,
			String keyValueSeparator, String pairSeparator) {
		// TODO: trace
//        Fx.Assert(
//            audienceFieldName != null && expiresOnFieldName != null && keyValueSeparator != null && pairSeparator != null,
//            "audienceFieldName, expiresOnFieldName, keyValueSeparator, and pairSeparator are all required");

		if (tokenString == null) {
			throw new IllegalArgumentException("tokenString cannot be null");
		}

		this.token = tokenString;
		this.audienceFieldName = audienceFieldName;
		this.expiresOnFieldName = expiresOnFieldName;
		this.keyValueSeparator = keyValueSeparator;
		this.pairSeparator = pairSeparator;
		getExpirationDateAndAudienceFromToken(tokenString);
	}

	private void getExpirationDateAndAudienceFromToken(String tokenString) {
		HashMap<String, String> decodedToken = getDecodedTokenMap(tokenString, StandardCharsets.UTF_8.name(),
				StandardCharsets.UTF_8.name(), this.keyValueSeparator, this.pairSeparator);
		String expiresOn = decodedToken.get(this.expiresOnFieldName);
		if (expiresOn == null) {
			throw new IllegalArgumentException("tokenString missing expiresOn field");
		}

		if ((this.audience = decodedToken.get(this.audienceFieldName)) == null) {
			throw new IllegalArgumentException("tokenstring missing audience field");
		}

		this.expiresAtUtc = Instant.ofEpochSecond(Long.parseLong(expiresOn));
	}

	static HashMap<String, String> getDecodedTokenMap(String tokenString, String keyEncodingScheme,
			String valueEncodingScheme, String keyValueSeparator, String pairSeparator) {
		
		HashMap<String, String> map = new HashMap<String, String>();
		String[] valueEncodedPairs = tokenString.split(pairSeparator);
		
		for (String valueEncodedPair : valueEncodedPairs) {
			
			String[] pair = valueEncodedPair.split(keyValueSeparator);
			if (pair.length != 2) {
				throw new IllegalArgumentException("invalid encoding of tokenString.");
			}

			try {
				map.put(URLDecoder.decode(pair[0], keyEncodingScheme), URLDecoder.decode(pair[1], valueEncodingScheme));
			} 
			catch (UnsupportedEncodingException e) {
				throw new RuntimeException(keyEncodingScheme
						+ ((keyEncodingScheme.equals(valueEncodingScheme)) ? "" : " or " + valueEncodingScheme)
						+ " decoding is not supported in the java runtime.");
			}
		}

		return map;
	}
}
