package com.microsoft.azure.relay;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

public final class HybridConnectionUtil {
	
	// Equivalent of URI.GetComponents(UriComponents.SchemeAndServer | UriComponents.Path, UriFormat.UriEscaped)
    protected static String getAudience(URI uri) {
    	StringBuilder audience = new StringBuilder();
    	String scheme = uri.getScheme();
    	String host = uri.getHost();
    	int port = uri.getPort();
    	String path = uri.getPath();
    	
    	if (!StringUtil.isNullOrEmpty(scheme)) {
    		audience.append(scheme).append("://");
    	}
    	if (!StringUtil.isNullOrEmpty(host)) {
    		audience.append(host);
    	}
    	// if port is not defined, uri.getPort will return -1
    	if (port > -1) {
    		audience.append(":").append(port);
    	}
    	if (StringUtil.isNullOrEmpty(path)) {
    		audience.append("/").append(path);
    	}
    	
    	return audience.toString();
    }
    
    public static URI BuildUri(String host, int port, String path, String query, String action, String id) throws URISyntaxException {
        if (path.charAt(0) != '/') {
            path = "/" + path;
        }

        query = buildQueryString(query, action, id);
        return new URI(
        	HybridConnectionConstants.SECURE_WEBSOCKET_SCHEME,
        	null,
        	host,
        	port,
        	HybridConnectionConstants.HYBRIDCONNECTION_REQUEST_URI + path,
        	query,
        	null
        );
    }
    
    public static String buildQueryString(String existingQueryString, String action, String id) {
        StringBuilder buffer = new StringBuilder();

        if (!StringUtil.isNullOrEmpty(existingQueryString)) {
        	buffer.append(existingQueryString);
        	if (buffer.charAt(0) == '?' ) {
        		buffer.deleteCharAt(0);
        	}
            if (buffer.length() > 0) {
                buffer.append("&");
            }
        }

        buffer.append(HybridConnectionConstants.ACTION).append('=').append(action).append('&').append(HybridConnectionConstants.ID).append('=').append(id);
        return buffer.toString();
    }
    
    public static String filterQueryString(String queryString) {
        
    	if (StringUtil.isNullOrEmpty(queryString)) {
            return "";
        }
        StringBuffer buffer = new StringBuffer(queryString);
        if (buffer.charAt(0) == '?') {
        	buffer.deleteCharAt(0);
        }
        Map<String, String> queryStringCollection = parseQueryString(queryString);


        StringBuffer sb = new StringBuffer(256);
        
        for (String key : queryStringCollection.keySet()) {
            if (key == null || key.startsWith(HybridConnectionConstants.QUERY_STRING_KEY_PREFIX)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            try {
				sb.append(URLEncoder.encode(key, StringUtil.UTF8.name()))
					.append("=")
					.append(URLEncoder.encode(queryStringCollection.get(key), StringUtil.UTF8.name()));
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }

        return sb.toString();
    }
    
    public static Map<String, String> parseQueryString(String query) {
    	Map<String, String> map = new HashMap<String, String>();
    	
    	if (StringUtil.isNullOrEmpty(query)) {
    		return map;
    	}
    	
    	String[] pairs = query.split("&");
    	for (String pair : pairs) {
    		String[] keyValue = pair.split("=");
    		if (keyValue.length != 2) {
    			throw new IllegalArgumentException("invalid query to be parsed.");
    		}
    		map.put(keyValue[0], keyValue[1]);
    	}
    	
    	return map;
    }
}
