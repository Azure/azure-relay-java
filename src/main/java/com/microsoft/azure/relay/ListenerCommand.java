package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

public class ListenerCommand {
	private final static String ACCEPT = "accept";
	private final static String RENEW_TOKEN = "renewToken";
	private final static String REQUEST = "request";
	private final static String RESPONSE = "response";
	private final static String INJECT_FAULT = "injectFault";
	public final static Set<String> COMMANDS = new HashSet<String>(Arrays.asList(ACCEPT, RENEW_TOKEN,REQUEST, RESPONSE, INJECT_FAULT));
	
	private AcceptCommand accept;
	private RenewTokenCommand renewToken;
	private RequestCommand request;
	private ResponseCommand response;
	private InjectFaultCommand injectFault;

	public AcceptCommand getAccept() {
		return accept;
	}
	public void setAccept(AcceptCommand accept) {
		this.accept = accept;
	}
	public RenewTokenCommand getRenewToken() {
		return renewToken;
	}
	public void setRenewToken(RenewTokenCommand renewToken) {
		this.renewToken = renewToken;
	}
	public RequestCommand getRequest() {
		return request;
	}
	public void setRequest(RequestCommand request) {
		this.request = request;
	}
	public ResponseCommand getResponse() {
		return response;
	}
	public void setResponse(ResponseCommand response) {
		this.response = response;
	}
	public InjectFaultCommand getInjectFault() {
		return injectFault;
	}
	public void setInjectFault(InjectFaultCommand injectFault) {
		this.injectFault = injectFault;
	}
	
	public ListenerCommand (JSONObject json) {
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
	
	public class AcceptCommand {
		private String address;
		private String id;
		private Map<String, String> connectHeaders;
		private Endpoint remoteEndpoint;
		
		public String getAddress() {
			return address;
		}
		public void setAddress(String address) {
			this.address = address;
		}
		public String getId() {
			return id;
		}
		public void setId(String id) {
			this.id = id;
		}
		public Map<String, String> getConnectHeaders() {
			return (this.connectHeaders == null) ? new HashMap<String, String>() : this.connectHeaders;
		}
		public void setConnectHeaders(Map<String, String> connectHeaders) {
			this.connectHeaders = connectHeaders;
		}
		public Endpoint getRemoteEndpoint() {
			return (this.remoteEndpoint == null) ? new Endpoint() : this.remoteEndpoint;
		}
		public void setRemoteEndpoint(Endpoint remoteEndpoint) {
			this.remoteEndpoint = remoteEndpoint;
		}
		
		public AcceptCommand(JSONObject json) {
			this.address = json.optString("address");
			this.id = json.optString("id");
			this.remoteEndpoint = new Endpoint(json.getJSONObject("remoteEndpoint"));
			Map<String, Object> headers = json.getJSONObject("connectHeaders").toMap();
			this.connectHeaders = new HashMap<String, String>();
			headers.forEach((k, v) -> this.connectHeaders.put(k, (String)v));
		}
	}
	
	public class RequestCommand {
		private String address;
		private String id;
		private String requestTarget;
		private String method;
		private Endpoint remoteEndpoint;
		private Map<String, String> requestHeaders;
		private boolean body;
		
		public String getAddress() {
			return address;
		}
		public void setAddress(String address) {
			this.address = address;
		}
		public String getId() {
			return id;
		}
		public void setId(String id) {
			this.id = id;
		}
		public String getRequestTarget() {
			return requestTarget;
		}
		public void setRequestTarget(String requestTarget) {
			this.requestTarget = requestTarget;
		}
		public String getMethod() {
			return method;
		}
		public void setMethod(String method) {
			this.method = method;
		}
		public Endpoint getRemoteEndpoint() {
			return (this.remoteEndpoint == null) ? new Endpoint() : remoteEndpoint;
		}
		public void setRemoteEndpoint(Endpoint remoteEndpoint) {
			this.remoteEndpoint = remoteEndpoint;
		}
		public Map<String, String> getRequestHeaders() {
			return (this.requestHeaders == null) ? new HashMap<String, String>() : this.requestHeaders;
		}
		public void setRequestHeaders(Map<String, String> requestHeaders) {
			this.requestHeaders = requestHeaders;
		}
		public boolean hasBody() {
			return body;
		}
		public void setBody(boolean body) {
			this.body = body;
		}
		
		public RequestCommand(JSONObject json) {
			this.address = json.optString("address");
			this.id = json.optString("id");
			this.requestTarget = json.optString("requestTarget");
			this.method = json.optString("method");
			this.body = json.optBoolean("body");
			this.remoteEndpoint = new Endpoint(json.getJSONObject("remoteEndpoint"));
			Map<String, Object> headers = json.getJSONObject("requestHeaders").toMap();
			this.requestHeaders = new HashMap<String, String>();
			headers.forEach((k, v) -> this.requestHeaders.put(k, (String)v));
		}
	}
	
	public class RenewTokenCommand {
		private String token;

		public String getToken() {
			return token;
		}
		public void setToken(String token) {
			this.token = token;
		}
		
		public RenewTokenCommand(JSONObject json) {
			this.token = json.optString("token");
		}
	}
	
	public class ResponseCommand {
		private String requestId;
		private int statusCode;
		private String statusDescription;
		private Map<String, String> responseHeaders;
		private Boolean body;
		
		public String getRequestId() {
			return requestId;
		}
		public void setRequestId(String requestId) {
			this.requestId = requestId;
		}
		public int getStatusCode() {
			return statusCode;
		}
		public void setStatusCode(int statusCode) {
			this.statusCode = statusCode;
		}
		public String getStatusDescription() {
			return statusDescription;
		}
		public void setStatusDescription(String statusDescription) {
			this.statusDescription = statusDescription;
		}
		public Map<String, String> getResponseHeaders() {
			return (this.responseHeaders == null) ? new HashMap<String, String>() : responseHeaders;
		}
		public void setResponseHeaders(Map<String, String> responseHeaders) {
			this.responseHeaders = responseHeaders;
		}
		public Boolean hasBody() {
			return body;
		}
		public void setBody(Boolean body) {
			this.body = body;
		}
		
		public ResponseCommand() { }
		
		public ResponseCommand(JSONObject json) {
			this.requestId = json.optString("id");
			this.statusCode = json.optInt("statusCode");
			this.statusDescription = json.optString("statusDescription");
			this.body = json.optBoolean("body");
			Map<String, Object> headers = json.getJSONObject("responseHeaders").toMap();
			this.responseHeaders = new HashMap<String, String>();
			headers.forEach((k, v) -> this.responseHeaders.put(k, (String)v));
		}
	}
	
    public class InjectFaultCommand {
    	private Duration delay;

		public Duration getDelay() {
			return delay;
		}
		public void setDelay(Duration delay) {
			this.delay = delay;
		}
    }
    
	public class Endpoint {
		private String address;
		private int port;
		
		public String getAddress() {
			return address;
		}
		public void setAddress(String address) {
			this.address = address;
		}
		public int getPort() {
			return port;
		}
		public void setPort(int port) {
			this.port = port;
		}
		
		public Endpoint() {}
		
		public Endpoint(JSONObject json) {
			this.port = json.optInt("port");
			this.address = json.optString("address");
		}
	}
}
