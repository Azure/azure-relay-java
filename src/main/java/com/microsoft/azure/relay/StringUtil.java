// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

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
}
