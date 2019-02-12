package com.microsoft.azure.relay;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.resource.spi.IllegalStateException;

import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;

final class RelayLogger {
	static final Logger LOGGER = create();
	private static Map<String, String> Messages;

	private RelayLogger() {}
	
	private static Logger create() {
		init();
		return Logger.getLogger(RelayLogger.class);
	}
	
	public static void logEvent(String event, Object source, String... args) {
		TraceDetails details = prepareTrace(source);
		
		if (args == null || args.length == 0) {
			LOGGER.info(String.format(Messages.get(event), details.getSource()));
		}
		if (args.length == 1) {
			LOGGER.info(String.format(Messages.get(event), details.getSource(), args[0]));
		}
		if (args.length == 2) {
			LOGGER.info(String.format(Messages.get(event), details.getSource(), args[0], args[1]));
		}
		if (args.length == 3) {
			LOGGER.info(String.format(Messages.get(event), details.getSource(), args[0], args[1], args[2]));
		}
	}

	static RuntimeException argumentNull(String arg, Object source) {
		return throwingException(new IllegalArgumentException(arg + " is null or empty."), source, TraceLevel.ERROR);
	}
	
	public static RuntimeException invalidOperation(String msg, Object source) {
		return throwingException(new IllegalStateException("Invalid operation: " + msg), source);
	}

	public static void handleExceptionAsWarning(Exception exception, Object source) {
		throwingException(exception, source, TraceLevel.WARNING);
	}

	public static RuntimeException throwingException(Exception exception, Object source) {
		return throwingException(exception, source, TraceLevel.ERROR);
	}

	public static RuntimeException throwingException(Exception exception, Object source, TraceLevel level) {
		TraceDetails details = prepareTrace(source);
		String message = details.getSource() + " is throwing an Exception: ";

		switch (level) {
		case FATAL:
			LOGGER.fatal(message, exception);
			break;
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

		// This allows "throw ServiceBusEventSource.Log.ThrowingException(..."
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

	private static boolean init() {
		DOMConfigurator.configure("resources/log4j.xml");

		Map<String, String> map = new HashMap<String, String>();
		map.put("connecting", "%s is connecting.");
		map.put("connected", "%s is connected.");
		map.put("closing", "%s is closing.");
		map.put("closed", "%s is closed.");
		map.put("offline", "%s is offline.");
		map.put("rendezvousStart", "%s: Relay Listener Received a connection request. Rendezvous Address: %s.");
		map.put("rendezvousFailed", "%s: Relayed Listener failed to accept client. Exception: %s.");
		map.put("rendezvousClose", "%s: Relayed Listener has received call to close and will not accept the incoming connection. ConnectionAddress: %s");
		map.put("rendezvousStop", "%s: Relay Listener accepted a client connection.");
		map.put("rendezvousRejected", "%s: Relayed Listener is rejecting the client. StatusCode: %s, StatusDescription: %s.");
		map.put("sendCommand", "%s: Response command was sent: %s");
		map.put("httpRequestStarting", "%s: request initializing.");
		map.put("httpReadRendezvous", "%s: reading %s from the rendezvous connection.");
		map.put("httpRequestReceived", "%s: Request method: %s.");
		map.put("httpInvokeUserHandler", "%s: Invoking user RequestHandler.");
		map.put("httpUserRequestHandlerException", "%s: exception in the user's request handler.");
		map.put("httpMissingRequestHandler", "%s: No request handler is configured on the listener.");
		map.put("httpSendResponse", "%s: Sending the response command on the %s connection, status: %s.");
		map.put("httpSendResponseFinished", "%s: Finished sending the response command on the %s connection, status: %s.");
		map.put("httpSendingBytes", "%s: Sending %s bytes on the rendezvous connection");
		map.put("httpCreateRendezvous", "%s: Creating the rendezvous connection.");
		map.put("httpResponseStreamFlush", "%s+ResponseStream: FlushCoreAsync(reason=%s)");
		map.put("httpResponseStreamWrite", "%s+ResponseStream: WriteAsync(count=%s)");
		map.put("parsingUUIDFailed", "%s: Parsing TrackingId '%s' as Guid failed, created new ActivityId '%s' for trace correlation.");
		map.put("tokenRenewScheduled", "%s: Scheduling Token renewal after %s.");
		map.put("tokenRenewNegativeDuration", "%s: Not renewing token because the duration left on the token is negative.");
		map.put("getTokenStart", "%s: getToken start.");
		map.put("getTokenStop", "%s: getToken stop. New token expires at %s.");
		map.put("objectNotSet", "%s: %s was not set to the given value.");
		map.put("writingBytes", "%s: starting to write to remote. Writemode: %s");
		map.put("doneWritingBytes", "%s: finished writing %s bytes to remote.");
		map.put("writingBytesFailed", "%s: writing bytes failed.");
		map.put("receivedBytes", "%s: received bytes from remote. Total length: %s");
		map.put("receivedText", "%s: received text from remote. Total length: %s");

		Messages = Collections.unmodifiableMap(map);
		return true;
	}
}