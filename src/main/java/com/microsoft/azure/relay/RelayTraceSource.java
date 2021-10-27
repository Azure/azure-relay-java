// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

public interface RelayTraceSource {
	TrackingContext getTrackingContext();
	
	@Override
	String toString();
}
