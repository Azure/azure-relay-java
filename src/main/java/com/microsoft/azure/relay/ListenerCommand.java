package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

class ListenerCommand {
	private final static String ACCEPT = "accept";
	private final static String RENEW_TOKEN = "renewToken";
	private final static String REQUEST = "request";
	private final static String RESPONSE = "response";
	private final static String INJECT_FAULT = "injectFault";
	protected final static Set<String> COMMANDS = new HashSet<String>(Arrays.asList(ACCEPT, RENEW_TOKEN,REQUEST, RESPONSE, INJECT_FAULT));
	
	private AcceptCommand accept;
	private RenewTokenCommand renewToken;
	private RequestCommand request;
	private ResponseCommand response;
	private InjectFaultCommand injectFault;

	protected AcceptCommand getAccept() {
		return accept;
	}
	protected void setAccept(AcceptCommand accept) {
		this.accept = accept;
	}
	protected RenewTokenCommand getRenewToken() {
		return renewToken;
	}
	protected void setRenewToken(RenewTokenCommand renewToken) {
		this.renewToken = renewToken;
	}
	protected RequestCommand getRequest() {
		return request;
	}
	protected void setRequest(RequestCommand request) {
		this.request = request;
	}
	protected ResponseCommand getResponse() {
		return response;
	}
	protected void setResponse(ResponseCommand response) {
		this.response = response;
	}
	protected InjectFaultCommand getInjectFault() {
		return injectFault;
	}
	protected void setInjectFault(InjectFaultCommand injectFault) {
		this.injectFault = injectFault;
	}
	
	protected ListenerCommand (JSONObject json) {
		if (json == null || json.isEmpty()) {
			return;
		}
		
		Iterator<String> commands = json.keys();
		while(commands.hasNext()) {
			String command = commands.next();
			
			switch(command) {
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
					throw new IllegalArgumentException("invalid commnd type received.");
			}
		}
	}
	
	protected class AcceptCommand {
		private String address;
		private String id;
		private Map<String, String> connectHeaders;
		private Endpoint remoteEndpoint;
		
		protected String getAddress() {
			return address;
		}
		protected void setAddress(String address) {
			this.address = address;
		}
		protected String getId() {
			return id;
		}
		protected void setId(String id) {
			this.id = id;
		}
		protected Map<String, String> getConnectHeaders() {
			return (this.connectHeaders == null) ? new HashMap<String, String>() : this.connectHeaders;
		}
		protected void setConnectHeaders(Map<String, String> connectHeaders) {
			this.connectHeaders = connectHeaders;
		}
		protected Endpoint getRemoteEndpoint() {
			return (this.remoteEndpoint == null) ? new Endpoint() : this.remoteEndpoint;
		}
		protected void setRemoteEndpoint(Endpoint remoteEndpoint) {
			this.remoteEndpoint = remoteEndpoint;
		}
		
		protected AcceptCommand(JSONObject json) {
			this.address = json.optString("address");
			this.id = json.optString("id");
			this.remoteEndpoint = new Endpoint(json.getJSONObject("remoteEndpoint"));
			Map<String, Object> headers = json.getJSONObject("connectHeaders").toMap();
			this.connectHeaders = new HashMap<String, String>();
			headers.forEach((k, v) -> this.connectHeaders.put(k, (String)v));
		}
	}
	
	protected class RequestCommand {
		private String address;
		private String id;
		private String requestTarget;
		private String method;
		private Endpoint remoteEndpoint;
		private Map<String, String> requestHeaders;
		private Boolean body;
		
		protected String getAddress() {
			return address;
		}
		protected void setAddress(String address) {
			this.address = address;
		}
		protected String getId() {
			return id;
		}
		protected void setId(String id) {
			this.id = id;
		}
		protected String getRequestTarget() {
			return requestTarget;
		}
		protected void setRequestTarget(String requestTarget) {
			this.requestTarget = requestTarget;
		}
		protected String getMethod() {
			return method;
		}
		protected void setMethod(String method) {
			this.method = method;
		}
		protected Endpoint getRemoteEndpoint() {
			return (this.remoteEndpoint == null) ? new Endpoint() : remoteEndpoint;
		}
		protected void setRemoteEndpoint(Endpoint remoteEndpoint) {
			this.remoteEndpoint = remoteEndpoint;
		}
		protected Map<String, String> getRequestHeaders() {
			return (this.requestHeaders == null) ? new HashMap<String, String>() : this.requestHeaders;
		}
		protected void setRequestHeaders(Map<String, String> requestHeaders) {
			this.requestHeaders = requestHeaders;
		}
		protected Boolean hasBody() {
			return body;
		}
		protected void setBody(Boolean body) {
			this.body = body;
		}
		
		protected RequestCommand(JSONObject json) {
			this.address = json.optString("address");
			this.id = json.optString("id");
			this.requestTarget = json.optString("requestTarget");
			this.method = json.optString("method");
			this.body = json.has("body") ? json.optBoolean("body") : null;
			this.remoteEndpoint = json.has("remoteEndpoint") ? new Endpoint(json.getJSONObject("remoteEndpoint")) : null;
			if (json.has("requestHeaders")) {
				Map<String, Object> headers = json.getJSONObject("requestHeaders").toMap();
				this.requestHeaders = new HashMap<String, String>();
				headers.forEach((k, v) -> this.requestHeaders.put(k, (String)v));
			}
		}
	}
	
	protected class RenewTokenCommand {
		private String token;

		protected String getToken() {
			return token;
		}
		protected void setToken(String token) {
			this.token = token;
		}
		
		protected RenewTokenCommand(JSONObject json) {
			if (json != null) {
				this.token = json.optString("token");
			}
		}
	}
	
	protected class ResponseCommand {
		private String requestId;
		private int statusCode;
		private String statusDescription;
		private Map<String, String> responseHeaders;
		private Boolean body;
		
		protected String getRequestId() {
			return requestId;
		}
		protected void setRequestId(String requestId) {
			this.requestId = requestId;
		}
		protected int getStatusCode() {
			return statusCode;
		}
		protected void setStatusCode(int statusCode) {
			this.statusCode = statusCode;
		}
		protected String getStatusDescription() {
			return statusDescription;
		}
		protected void setStatusDescription(String statusDescription) {
			this.statusDescription = statusDescription;
		}
		protected Map<String, String> getResponseHeaders() {
			return (this.responseHeaders == null) ? new HashMap<String, String>() : responseHeaders;
		}
		protected void setResponseHeaders(Map<String, String> responseHeaders) {
			this.responseHeaders = responseHeaders;
		}
		protected Boolean hasBody() {
			return body;
		}
		protected void setBody(Boolean body) {
			this.body = body;
		}
		
		protected ResponseCommand() { }
		
		protected ResponseCommand(JSONObject json) {
			this.requestId = json.optString("id");
			this.statusCode = json.optInt("statusCode");
			this.statusDescription = json.optString("statusDescription");
			this.body = json.optBoolean("body");
			Map<String, Object> headers = json.getJSONObject("responseHeaders").toMap();
			this.responseHeaders = new HashMap<String, String>();
			headers.forEach((k, v) -> this.responseHeaders.put(k, (String)v));
		}
		
		protected String toJsonString() {
			StringBuilder builder = new StringBuilder("{\"response\":{");
			List<String> fields = new ArrayList<String>();
			
			if (this.requestId != null) {
				fields.add("\"requestId\":\"" + this.requestId + "\"");
			}
			
			if (this.body == null) {
				fields.add("\"body\":\"null\"");
			}
			else if (this.body == true) {
				fields.add("\"body\":\"true\"");
			}
			else if (this.body == false) {
				fields.add("\"body\":\"false\"");
			}
			
			fields.add("\"statusCode\":" + this.statusCode);
			
			if (this.statusDescription != null) {
				fields.add("\"statusDescription\":\"" + this.statusDescription + "\"");
			}
			
			builder.append(String.join(",", fields)).append("}}");
			return builder.toString();
		}
	}
	
    protected class InjectFaultCommand {
    	private Duration delay;

		protected Duration getDelay() {
			return delay;
		}
		protected void setDelay(Duration delay) {
			this.delay = delay;
		}
    }
    
	protected class Endpoint {
		private String address;
		private int port;
		
		protected String getAddress() {
			return address;
		}
		protected void setAddress(String address) {
			this.address = address;
		}
		protected int getPort() {
			return port;
		}
		protected void setPort(int port) {
			this.port = port;
		}
		
		protected Endpoint() {}
		
		protected Endpoint(JSONObject json) {
			this.port = json.optInt("port");
			this.address = json.optString("address");
		}
	}
}
