package com.microsoft.azure.relay;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class StringUtil {
	
	public final static Charset UTF8 = StandardCharsets.UTF_8;
	
    public static boolean isNullOrWhiteSpace(String s) {
    	return s == null || s.trim().isEmpty();
    }
    
    public static boolean isNullOrEmpty(String s) {
    	return s == null || s.isEmpty();
    }

    public static Map<String, String> parseConnectionString(String connectionString) {
    	HashMap<String, String> map = new HashMap<String, String>();
    	if (connectionString != null) {
    		for (String pair : connectionString.split(";")) {
    			int index = pair.indexOf("=");
    			map.put(pair.substring(0, index), pair.substring(index + 1));
    		}
    	}
    	return map;
    }
    
}
