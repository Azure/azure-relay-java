// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class SharedAccessSignatureTokenProvider extends TokenProvider {
	private static final String UTF8_ENCODING_NAME = StandardCharsets.UTF_8.name();
	private final byte[] encodedSharedAccessKey;
	private final String keyName;
	private final String sharedAccessSignature;

	public SharedAccessSignatureTokenProvider(String sharedAccessSignature) {
		SharedAccessSignatureToken.validate(sharedAccessSignature);
		this.encodedSharedAccessKey = null;
		this.keyName = null;
		this.sharedAccessSignature = sharedAccessSignature;
	}

	public SharedAccessSignatureTokenProvider(String keyName, String sharedAccessKey) {
		this(keyName, sharedAccessKey, null);
	}

	public SharedAccessSignatureTokenProvider(String keyName, String sharedAccessKey, Charset charset) {
		this.sharedAccessSignature = null;
		if (StringUtil.isNullOrEmpty(keyName) || StringUtil.isNullOrEmpty(sharedAccessKey)) {
			throw new IllegalArgumentException("keyName or key cannot be empty.");
		}

		if (keyName.length() > SharedAccessSignatureToken.MAX_KEYNAME_LENGTH) {
			throw new IllegalArgumentException("length of keyName is " + keyName.length()
					+ ", which exceeded the maximum of " + SharedAccessSignatureToken.MAX_KEYNAME_LENGTH);
		}

		if (sharedAccessKey.length() > SharedAccessSignatureToken.MAX_KEY_LENGTH) {
			throw new IllegalArgumentException("length of keyName is " + sharedAccessKey.length()
					+ ", which exceeded the maximum of " + SharedAccessSignatureToken.MAX_KEY_LENGTH);
		}

		this.keyName = keyName;
		Charset encodingCharset = charset == null ? StringUtil.UTF8 : charset;
		this.encodedSharedAccessKey = sharedAccessKey.getBytes(encodingCharset);
	}

	@Override
	protected CompletableFuture<SecurityToken> onGetTokenAsync(String resource, Duration validFor) {
		try {
			String tokenString = this.buildSignature(resource, validFor);
			SharedAccessSignatureToken securityToken = new SharedAccessSignatureToken(tokenString);
			return CompletableFuture.completedFuture(securityToken);
		} 
		catch (InvalidKeyException | UnsupportedEncodingException | NoSuchAlgorithmException e) {
			return CompletableFutureUtil.fromException(e);
		}
	}

	protected String buildSignature(String resource, Duration validFor) throws InvalidKeyException, UnsupportedEncodingException, NoSuchAlgorithmException {
		if (StringUtil.isNullOrWhiteSpace(this.sharedAccessSignature)) {
			return SharedAccessSignatureBuilder.buildSignature(this.keyName, this.encodedSharedAccessKey, resource,
					validFor);
		}

		return this.sharedAccessSignature;
	}

	static class SharedAccessSignatureBuilder {
		static final String HMAC_ALGORITHM = "HMACSHA256";

		public static String buildSignature(String keyName, byte[] encodedSharedAccessKey, String resource,
				Duration timeToLive) throws UnsupportedEncodingException, InvalidKeyException, NoSuchAlgorithmException {
			
			// Note that target URI is not normalized because in IoT scenario it
			// is case sensitive.
				String expiresOn = buildExpiresOn(timeToLive);
				String audienceUri = URLEncoder.encode(resource, UTF8_ENCODING_NAME);
				String[] fields = new String[] { audienceUri, expiresOn };

				// Example String to be signed:
				// http://mynamespace.servicebus.windows.net/a/b/c?myvalue1=a
				// <Value for ExpiresOn>
				String signature = SharedAccessSignatureBuilder.sign(String.join("\n", fields), encodedSharedAccessKey);

				// Example returned String:
				// SharedAccessKeySignature
				// sr=ENCODED(http://mynamespace.servicebus.windows.net/a/b/c?myvalue1=a)&sig=<Signature>&se=<ExpiresOnValue>&skn=<KeyName>

				return String.format(Locale.ROOT, "%s %s=%s&%s=%s&%s=%s&%s=%s", new Object[] {
						SharedAccessSignatureToken.SHARED_ACCESS_SIGNATURE, SharedAccessSignatureToken.SIGNED_RESOURCE,
						audienceUri, SharedAccessSignatureToken.SIGNATURE,
						URLEncoder.encode(signature, UTF8_ENCODING_NAME), SharedAccessSignatureToken.SIGNED_EXPIRY,
						URLEncoder.encode(expiresOn, UTF8_ENCODING_NAME), SharedAccessSignatureToken.SIGNATURE_KEYNAME,
						URLEncoder.encode(keyName, UTF8_ENCODING_NAME) });
		}

		static String buildExpiresOn(Duration timeToLive) {
			long timeToLiveInSeconds = timeToLive.getSeconds();
			if (timeToLiveInSeconds < 0) {
				throw new IllegalArgumentException("timeToLive should be a positive value");
			}
			long expireOnInSeconds = Instant.now().getEpochSecond() + timeToLiveInSeconds;
			return String.valueOf(expireOnInSeconds);
		}

		static String sign(String requestString, byte[] encodedSharedAccessKey) throws InvalidKeyException, NoSuchAlgorithmException {
			Mac hmac = Mac.getInstance(HMAC_ALGORITHM);
			SecretKeySpec secretKey = new SecretKeySpec(encodedSharedAccessKey, HMAC_ALGORITHM);
			hmac.init(secretKey);
			byte[] signatureBytes = hmac.doFinal(requestString.getBytes(StringUtil.UTF8));
			return Base64.getEncoder().encodeToString(signatureBytes);
		}
	}

	// A SecurityToken that wraps a Shared Access Signature
	static class SharedAccessSignatureToken extends SecurityToken {
		public static final int MAX_KEYNAME_LENGTH = 256;
		public static final int MAX_KEY_LENGTH = 256;
		public static final String SHARED_ACCESS_SIGNATURE = "SharedAccessSignature";
		public static final String SIGNED_RESOURCE = "sr";
		public static final String SIGNATURE = "sig";
		public static final String SIGNATURE_KEYNAME = "skn";
		public static final String SIGNED_EXPIRY = "se";
		public static final String SIGNED_RESOURCE_FULL_FIELD_NAME = SHARED_ACCESS_SIGNATURE + " " + SIGNED_RESOURCE;
		public static final String SAS_KEY_VALUE_SEPARATOR = "=";
		public static final String SAS_PAIR_SEPARATOR = "&";

		public SharedAccessSignatureToken(String tokenString) {
			super(tokenString, SIGNED_RESOURCE_FULL_FIELD_NAME, SIGNED_EXPIRY, SAS_KEY_VALUE_SEPARATOR,
					SAS_PAIR_SEPARATOR);
		}

		static void validate(String sharedAccessSignature) {
			if (StringUtil.isNullOrEmpty(sharedAccessSignature)) {
				throw new IllegalArgumentException("sharedAccessSignature cannot be null or empty");
			}

			Map<String, String> parsedFields = extractFieldValues(sharedAccessSignature);
			for (String field : new String[] { SIGNATURE, SIGNED_EXPIRY, SIGNATURE_KEYNAME, SIGNED_RESOURCE }) {
				if (parsedFields.get(field) == null)
					throw new IllegalArgumentException(field.toString() + " has no corresponding value.");
			}
		}

		static Map<String, String> extractFieldValues(String sharedAccessSignature) {
			String[] tokenLines = sharedAccessSignature.split("\\s+");

			if (!tokenLines[0].trim().equalsIgnoreCase(SHARED_ACCESS_SIGNATURE) || tokenLines.length != 2) {
				throw new IllegalArgumentException("invalid sharedAccessSignture.");
			}

			Map<String, String> parsedFields = new HashMap<String, String>();
			String[] tokenFields = tokenLines[1].trim().split(SAS_PAIR_SEPARATOR);

			for (String tokenField : tokenFields) {
				if (!StringUtil.isNullOrEmpty(tokenField)) {
					String[] fieldParts = tokenField.split(SAS_KEY_VALUE_SEPARATOR);
					String key = fieldParts[0].toLowerCase();
					// defer decoding to later for audience to preserve the escape characters
					try {
						parsedFields.put(key, (key.equalsIgnoreCase(SIGNED_RESOURCE)) ? fieldParts[1]
								: URLDecoder.decode(fieldParts[1], UTF8_ENCODING_NAME));
					} catch (UnsupportedEncodingException e) {
						throw new RuntimeException("UTF-8 decoding is not supported in the java runtime.");
					}
				}
			}

			return parsedFields;
		}
	}
}
