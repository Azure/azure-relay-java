package com.microsoft.azure.relay;

interface ThrowingSupplier<T, E extends Exception> {
	
	T supply() throws E;
	
	@SuppressWarnings("unchecked")
	static <E extends Exception, R> R throwException(Exception e) throws E {
	    throw (E) e;
	}
}
