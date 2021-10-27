// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.websocket.ClientEndpointConfig;

class HybridConnectionEndpointConfigurator extends ClientEndpointConfig.Configurator {
	private Map<String, List<String>> customHeaders;
	
	HybridConnectionEndpointConfigurator() {
		this.customHeaders = new HashMap<String, List<String>>();
	}
	
	void addHeaders(Map<String, List<String>> headers) {
		if (headers != null && !headers.isEmpty()) {
			this.customHeaders.putAll(headers);
		}
	}
	
	@Override
	public void beforeRequest(Map<String, List<String>> requestHeaders) {
		requestHeaders.putAll(customHeaders);
	}
}
