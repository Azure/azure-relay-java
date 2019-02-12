package com.microsoft.azure.relay;

class TraceDetails {
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

	protected TrackingContext getTrackingContext() {
		return trackingContext;
	}

	protected void setTrackingContext(TrackingContext trackingContext) {
		this.trackingContext = trackingContext;
	}
}
