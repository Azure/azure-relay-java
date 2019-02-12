package com.microsoft.azure.relay;

public interface RelayTraceSource {
	TrackingContext getTrackingContext();
	
	@Override
	String toString();
}
