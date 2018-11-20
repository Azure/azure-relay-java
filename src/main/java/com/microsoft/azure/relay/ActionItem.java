package com.microsoft.azure.relay;

import java.util.function.Consumer;

public class ActionItem {
	
    public static void schedule(Consumer<Object> action, Object params)
    {
    	action.accept(params);
    }
}
