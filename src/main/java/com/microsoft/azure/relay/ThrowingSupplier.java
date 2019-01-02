package com.microsoft.azure.relay;

// Work around used to throw exceptions from within lambdas to be caught elsewhere
interface ThrowingSupplier<T, E extends Exception> {
	
	T supply() throws E;
	
	@SuppressWarnings("unchecked")
	static <E extends Exception, R> R throwException(Exception e) throws E {
	    throw (E) e;
	}
}
