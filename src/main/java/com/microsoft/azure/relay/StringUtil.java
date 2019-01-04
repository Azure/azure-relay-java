package com.microsoft.azure.relay;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class StringUtil {

	public final static Charset UTF8 = StandardCharsets.UTF_8;

	public static boolean isNullOrWhiteSpace(String s) {
		return s == null || s.trim().isEmpty();
	}

	public static boolean isNullOrEmpty(String s) {
		return s == null || s.isEmpty();
	}
}
