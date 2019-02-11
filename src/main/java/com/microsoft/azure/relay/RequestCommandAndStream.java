package com.microsoft.azure.relay;

import java.io.ByteArrayInputStream;

final class RequestCommandAndStream {
	private ListenerCommand.RequestCommand requestCommand;
	private ByteArrayInputStream stream;

	public ListenerCommand.RequestCommand getRequestCommand() {
		return requestCommand;
	}

	public void setRequestCommand(ListenerCommand.RequestCommand requestCommand) {
		this.requestCommand = requestCommand;
	}

	public ByteArrayInputStream getStream() {
		return stream;
	}

	public void setStream(ByteArrayInputStream stream) {
		this.stream = stream;
	}

	public RequestCommandAndStream(ListenerCommand.RequestCommand requestCommand, ByteArrayInputStream stream) {
		this.requestCommand = requestCommand;
		this.stream = stream;
	}
}