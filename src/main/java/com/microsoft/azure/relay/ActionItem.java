package com.microsoft.azure.relay;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

class ActionItem {

	protected static void schedule(Consumer<Object> action, Object params) {
		CompletableFuture.runAsync(() -> action.accept(params));
	}
}
