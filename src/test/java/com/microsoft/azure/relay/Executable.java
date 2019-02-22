package com.microsoft.azure.relay;

@FunctionalInterface
public interface Executable {
	void execute() throws Throwable;
}