package com.microsoft.azure.relay;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class StringUtil {

	final static Charset UTF8 = StandardCharsets.UTF_8;

	static boolean isNullOrWhiteSpace(String s) {
		return s == null || s.trim().isEmpty();
	}

	static boolean isNullOrEmpty(String s) {
		return s == null || s.isEmpty();
	}
	
	static ByteBuffer toBuffer(String str) {
		return ByteBuffer.wrap(str.getBytes(UTF8));
	}
}
