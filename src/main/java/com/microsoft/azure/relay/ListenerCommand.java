package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

class ListenerCommand {
	private final static String ACCEPT = "accept";
	private final static String RENEW_TOKEN = "renewToken";
	private final static String REQUEST = "request";
	private final static String RESPONSE = "response";
	private final static String INJECT_FAULT = "injectFault";

	private AcceptCommand accept;
	private RenewTokenCommand renewToken;
	private RequestCommand request;
	private ResponseCommand response;
	private InjectFaultCommand injectFault;

	ListenerCommand(JSONObject json) {
		if (json == null || json.isEmpty()) {
			return;
		}

		// Despite the Iterator class returned by the json API, there should only be one command in this object
		Iterator<String> commands = json.keys();
		while (commands.hasNext()) {
			String command = commands.next();

			switch (command) {
			case ACCEPT:
				this.accept = new AcceptCommand(json.getJSONObject(ACCEPT));
				break;
			case RENEW_TOKEN:
				this.renewToken = new RenewTokenCommand(json.getJSONObject(RENEW_TOKEN));
				break;
			case REQUEST:
				this.request = new RequestCommand(json.getJSONObject(REQUEST));
				break;
			case RESPONSE:
				this.response = new ResponseCommand(json.getJSONObject(RESPONSE));
				break;
			case INJECT_FAULT:
				this.injectFault = new InjectFaultCommand();
				break;
			default:
				throw new IllegalArgumentException("Invalid command type received.");
			}
		}
	}
	
	AcceptCommand getAccept() {
		return accept;
	}

	RenewTokenCommand getRenewToken() {
		return renewToken;
	}

	RequestCommand getRequest() {
		return request;
	}

	ResponseCommand getResponse() {
		return response;
	}

	InjectFaultCommand getInjectFault() {
		return injectFault;
	}

	void setAccept(AcceptCommand accept) {
		this.accept = accept;
	}

	void setRenewToken(RenewTokenCommand renewToken) {
		this.renewToken = renewToken;
	}

	void setRequest(RequestCommand request) {
		this.request = request;
	}

	void setResponse(ResponseCommand response) {
		this.response = response;
	}

	void setInjectFault(InjectFaultCommand injectFault) {
		this.injectFault = injectFault;
	}
	
	class AcceptCommand {
		private String address;
		private String id;
		private Map<String, String> connectHeaders;
		private Endpoint remoteEndpoint;

		String getAddress() {
			return address;
		}

		void setAddress(String address) {
			this.address = address;
		}

		String getId() {
			return id;
		}

		void setId(String id) {
			this.id = id;
		}

		Map<String, String> getConnectHeaders() {
			if (this.connectHeaders == null) {
				this.connectHeaders = new HashMap<String, String>();
			}
			return this.connectHeaders;
		}

		void setConnectHeaders(Map<String, String> connectHeaders) {
			this.connectHeaders = connectHeaders;
		}

		Endpoint getRemoteEndpoint() {
			if (this.remoteEndpoint == null) {
				this.remoteEndpoint = new Endpoint();
			}
			return this.remoteEndpoint;
		}

		void setRemoteEndpoint(Endpoint remoteEndpoint) {
			this.remoteEndpoint = remoteEndpoint;
		}

		AcceptCommand(JSONObject json) {
			this.address = json.optString("address");
			this.id = json.optString("id");
			this.remoteEndpoint = new Endpoint(json.getJSONObject("remoteEndpoint"));
			Map<String, Object> headers = json.getJSONObject("connectHeaders").toMap();
			this.connectHeaders = new HashMap<String, String>();
			headers.forEach((k, v) -> this.connectHeaders.put(k, (String) v));
		}
	}

	class RequestCommand {
		private String address;
		private String id;
		private String requestTarget;
		private String method;
		private Endpoint remoteEndpoint;
		private Map<String, String> requestHeaders;
		private Boolean body;

		String getAddress() {
			return address;
		}

		void setAddress(String address) {
			this.address = address;
		}

		String getId() {
			return id;
		}

		void setId(String id) {
			this.id = id;
		}

		String getRequestTarget() {
			return requestTarget;
		}

		void setRequestTarget(String requestTarget) {
			this.requestTarget = requestTarget;
		}

		String getMethod() {
			return method;
		}

		void setMethod(String method) {
			this.method = method;
		}

		Endpoint getRemoteEndpoint() {
			if (this.remoteEndpoint == null) {
				this.remoteEndpoint = new Endpoint();
			}
			return this.remoteEndpoint;
		}

		void setRemoteEndpoint(Endpoint remoteEndpoint) {
			this.remoteEndpoint = remoteEndpoint;
		}

		Map<String, String> getRequestHeaders() {
			if (this.requestHeaders == null) {
				this.requestHeaders = new HashMap<String, String>();
			}
			return this.requestHeaders;
		}

		void setRequestHeaders(Map<String, String> requestHeaders) {
			this.requestHeaders = requestHeaders;
		}

		Boolean hasBody() {
			return body;
		}

		void setBody(Boolean body) {
			this.body = body;
		}

		RequestCommand(JSONObject json) {
			this.address = json.optString("address");
			this.id = json.optString("id");
			this.requestTarget = json.optString("requestTarget");
			this.method = json.optString("method");
			this.body = json.has("body") ? json.optBoolean("body") : null;
			this.remoteEndpoint = json.has("remoteEndpoint") ? new Endpoint(json.getJSONObject("remoteEndpoint"))
					: null;
			if (json.has("requestHeaders")) {
				Map<String, Object> headers = json.getJSONObject("requestHeaders").toMap();
				this.requestHeaders = new HashMap<String, String>();
				headers.forEach((k, v) -> this.requestHeaders.put(k, (String) v));
			}
		}
	}

	class RenewTokenCommand {
		static final String TOKEN_NAME = "token";
		private String token;

		String getToken() {
			return token;
		}

		void setToken(String token) {
			this.token = token;
		}

		RenewTokenCommand(JSONObject json) {
			if (json != null) {
				this.token = json.optString(TOKEN_NAME);
			}
		}
		
		String toJsonString() {
			return "{\"" + RENEW_TOKEN + "\":{\"" + TOKEN_NAME + "\":\"" + this.token + "\"}}";
		}
	}

	class ResponseCommand {
		private String requestId;
		private int statusCode;
		private String statusDescription;
		private Map<String, String> responseHeaders;
		private boolean body;

		String getRequestId() {
			return requestId;
		}

		void setRequestId(String requestId) {
			this.requestId = requestId;
		}

		int getStatusCode() {
			return statusCode;
		}

		void setStatusCode(int statusCode) {
			this.statusCode = statusCode;
		}

		String getStatusDescription() {
			return statusDescription;
		}

		void setStatusDescription(String statusDescription) {
			this.statusDescription = statusDescription;
		}

		Map<String, String> getResponseHeaders() {
			if (this.responseHeaders == null) {
				this.responseHeaders = new HashMap<String, String>();
			}
			return this.responseHeaders;
		}

		void setResponseHeaders(Map<String, String> responseHeaders) {
			this.responseHeaders = responseHeaders;
		}

		boolean hasBody() {
			return body;
		}

		void setBody(boolean body) {
			this.body = body;
		}

		ResponseCommand() {
		}

		ResponseCommand(JSONObject json) {
			this.requestId = json.getString("id");
			this.statusCode = json.getInt("statusCode");
			this.statusDescription = json.optString("statusDescription");
			this.body = json.getBoolean("body");
			Map<String, Object> headers = json.optJSONObject("responseHeaders").toMap();
			this.responseHeaders = new HashMap<String, String>();
			
			if (headers != null) {
				headers.forEach((k, v) -> this.responseHeaders.put(k, (String) v));
			}
		}

		String toJsonString() {
			StringBuilder builder = new StringBuilder("{\"" + HybridConnectionConstants.Actions.RESPONSE + "\":{");
			List<String> fields = new ArrayList<String>();

			if (this.requestId != null) {
				fields.add("\"requestId\":\"" + this.requestId + "\"");
			}
			
			fields.add("\"body\":" + ((this.body) ? "true" : "false"));
			fields.add("\"statusCode\":" + this.statusCode);

			if (this.statusDescription != null) {
				fields.add("\"statusDescription\":\"" + this.statusDescription + "\"");
			}

			builder.append(String.join(",", fields)).append("}}");
			return builder.toString();
		}
	}

	class InjectFaultCommand {
		private Duration delay;

		Duration getDelay() {
			return delay;
		}

		void setDelay(Duration delay) {
			this.delay = delay;
		}
	}

	class Endpoint {
		private String address;
		private int port;

		String getAddress() {
			return address;
		}

		void setAddress(String address) {
			this.address = address;
		}

		int getPort() {
			return port;
		}

		void setPort(int port) {
			this.port = port;
		}

		Endpoint() {
		}

		Endpoint(JSONObject json) {
			this.port = json.optInt("port");
			this.address = json.optString("address");
		}
	}
}
