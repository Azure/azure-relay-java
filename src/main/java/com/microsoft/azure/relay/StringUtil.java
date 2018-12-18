package com.microsoft.azure.relay;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class StringUtil {
	
	protected final static Charset UTF8 = StandardCharsets.UTF_8;
	
    protected static boolean isNullOrWhiteSpace(String s) {
    	return s == null || s.trim().isEmpty();
    }
    
    protected static boolean isNullOrEmpty(String s) {
    	return s == null || s.isEmpty();
    }
}
