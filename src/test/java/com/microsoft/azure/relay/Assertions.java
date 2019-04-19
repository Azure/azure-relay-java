package com.microsoft.azure.relay;

import java.util.concurrent.CompletableFuture;

import junit.framework.AssertionFailedError;

public final class Assertions {
	private Assertions() {		
	}	
	
	/**
	  * Asserts that the execution of the given Executable will throw the expected exception
	  * @param expectedType The class of the expected exception
	  * @param executable The code to be executed
	  * @return The actual exception that's thrown, if it matches the expected exception type
	  */
	@SuppressWarnings("unchecked")
	public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
		try {
			executable.execute();
		}
		catch (Throwable actualException) {
			if (expectedType.isInstance(actualException)) {
				return (T) actualException;
			}
			else {
				String message = "Expected exception of type '" + expectedType + "' but found exception of type '" + actualException.getClass() + "' instead.";
				throw new AssertionFailedError(message);
			}
		}

		String message = "Expected exception of type '" + expectedType + "' to be thrown.";
		throw new AssertionFailedError(message);
	}
	
	public static <T extends Throwable> T assertCFThrows(Class<T> expectedType, CompletableFuture<?> cf) {
		return assertThrows(expectedType, () -> {
			try {
				cf.join();
			} catch (Exception e) {
				throw e.getCause();
			}
		});
	}
}
