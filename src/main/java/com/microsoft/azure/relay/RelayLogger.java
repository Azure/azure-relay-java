// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RelayLogger {
	static final Logger LOGGER = create();
	private static Map<String, String> Messages;

	private RelayLogger() {}
	
	private static Logger create() {
		init();
		return LoggerFactory.getLogger(RelayLogger.class);
	}
	
	public static void logEvent(String event, Object source, String... args) {
		TraceDetails details = prepareTrace(source);
		
		if (args == null || args.length == 0) {
			LOGGER.info(String.format(Messages.get(event), details.getSource()));
		}
		else if (args.length == 1) {
			LOGGER.info(String.format(Messages.get(event), details.getSource(), args[0]));
		}
		else if (args.length == 2) {
			LOGGER.info(String.format(Messages.get(event), details.getSource(), args[0], args[1]));
		}
		else if (args.length == 3) {
			LOGGER.info(String.format(Messages.get(event), details.getSource(), args[0], args[1], args[2]));
		}
	}

	public static RuntimeException argumentNull(String arg, Object source) {
		return throwingException(new IllegalArgumentException(arg + " is null or empty."), source, TraceLevel.ERROR);
	}
	
	public static RuntimeException invalidOperation(String msg, Object source) {
		return throwingException(new IllegalStateException("Invalid operation: " + msg), source);
	}

	public static void handledExceptionAsWarning(Throwable exception, Object source) {
		throwingException(exception, source, TraceLevel.WARNING);
	}

	public static RuntimeException throwingException(Throwable exception, Object source) {
		return throwingException(exception, source, TraceLevel.ERROR);
	}

	public static RuntimeException throwingException(Throwable exception, Object source, TraceLevel level) {
		TraceDetails details = prepareTrace(source);
		String message = details.getSource() + " is throwing an Exception: ";

		switch (level) {
		case ERROR:
			LOGGER.error(message, exception);
			break;
		case WARNING:
			LOGGER.warn(message, exception);
			break;
		case DEBUG:
			LOGGER.debug(message, exception);
			break;
		default:
		case INFO:
			LOGGER.info(message, exception);
			break;
		}

		// This allows "throw RelayLogger.throwingException(..."
		return (exception instanceof RuntimeException) ? (RuntimeException) exception : new RuntimeException(exception);
	}

	private static TraceDetails prepareTrace(Object source) {
		String sourceString;
		TrackingContext trackingContext;

		if (source instanceof RelayTraceSource) {
			trackingContext = ((RelayTraceSource) source).getTrackingContext();
			sourceString = source.toString();
		} else {
			sourceString = createSourceString(source);
			trackingContext = null;
		}

		return new TraceDetails(sourceString, trackingContext);
	}

	@SuppressWarnings("rawtypes")
	private static String createSourceString(Object source) {
		if (source == null) {
			return "";
		} else if (source instanceof Class<?>) {
			return ((java.lang.Class) source).getSimpleName();
		}

		return source.toString();
	}

	private static void init() {
		Map<String, String> map = new HashMap<String, String>();
		
		map.put("clientWebSocketClosing", "%s: is closing. Close reason: %s");
		map.put("clientWebSocketClosed", "%s: is closed. Close reason: %s");
		map.put("closing", "%s is closing.");
		map.put("closed", "%s is closed.");
		map.put("connecting", "%s is connecting.");
		map.put("connected", "%s is connected.");
		map.put("disconnect", "%s: is disconnected, should reconnect = %s");
		map.put("getTokenStart", "%s: getToken start.");
		map.put("getTokenStop", "%s: getToken stop. New token expires at %s.");
		map.put("httpCreateRendezvous", "%s: Creating the rendezvous connection.");
		map.put("httpInvokeUserHandler", "%s: Invoking user RequestHandler.");
		map.put("httpMissingRequestHandler", "%s: No request handler is configured on the listener.");
		map.put("httpReadRendezvous", "%s: reading %s from the rendezvous connection.");
		map.put("httpRequestReceived", "%s: Request method: %s.");
		map.put("httpRequestStarting", "%s: request initializing.");
		map.put("httpResponseStreamFlush", "%s+ResponseStream: FlushCoreAsync(reason=%s)");
		map.put("httpResponseStreamWrite", "%s+ResponseStream: WriteAsync(count=%s)");
		map.put("httpSendingBytes", "%s: Sending %s bytes on the rendezvous connection");
		map.put("httpSendResponse", "%s: Sending the response command on the %s connection, status: %s.");
		map.put("httpSendResponseFinished", "%s: Finished sending the response command on the %s connection, status: %s.");
		map.put("httpWrittenToBuffer", "%s: finished writing %s bytes to ResponseStream buffer.");
		map.put("objectNotSet", "%s: %s was not set to the given value.");
		map.put("offline", "%s is offline.");
		map.put("parsingUUIDFailed", "%s: Parsing TrackingId '%s' as Guid failed, created new ActivityId '%s' for trace correlation.");
		map.put("receivedBytes", "%s: received bytes from remote. Total length: %s");
		map.put("receivedText", "%s: received text from remote. Total length: %s");
		map.put("rendezvousClose", "%s: Relayed Listener has received call to close and will not accept the incoming connection. ConnectionAddress: %s");
		map.put("rendezvousFailed", "%s: Relayed Listener failed to accept client. Exception: %s.");
		map.put("rendezvousRejected", "%s: Relayed Listener is rejecting the client. StatusCode: %s, StatusDescription: %s.");
		map.put("rendezvousStart", "%s: Relay Listener Received a connection request. Rendezvous Address: %s.");
		map.put("rendezvousStop", "%s: Relay Listener accepted a client connection.");
		map.put("sendCommand", "%s: Response command was sent: %s");
		map.put("tokenRenewNegativeDuration", "%s: Not renewing token because the duration left on the token is negative.");
		map.put("tokenRenewScheduled", "%s: Scheduling Token renewal after %s.");
		map.put("writingBytes", "%s: starting to write to remote. Writemode: %s");
		map.put("writingBytesFinished", "%s: finished writing %s bytes to remote.");
		map.put("writingBytesFailed", "%s: writing bytes failed.");

		Messages = Collections.unmodifiableMap(map);
	}
	
	private static class TraceDetails {
		private String source;
		private TrackingContext trackingContext;

		protected TraceDetails(String source, TrackingContext trackingContext) {
			this.setSource(source);
			this.setTrackingContext(trackingContext);
		}

		protected String getSource() {
			return source;
		}

		protected void setSource(String source) {
			this.source = source;
		}

		@SuppressWarnings("unused")
		protected TrackingContext getTrackingContext() {
			return trackingContext;
		}

		protected void setTrackingContext(TrackingContext trackingContext) {
			this.trackingContext = trackingContext;
		}
	}
}