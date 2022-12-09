// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

import java.time.Duration;

final class RelayConstants {

    private RelayConstants() { }

    static final String MANAGEMENT_API_VERSION = "2016-07";
    static final String MANAGEMENT_NAMESPACE = "http://schemas.microsoft.com/netservices/2010/10/servicebus/connect";
    static final String HYBRID_CONNECTION_SCHEME = "sb";
    static final String SERVICEBUS_AUTHORIZATION_HEADER_NAME = "ServiceBusAuthorization";
    static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofMinutes(1);
    static final Duration CLIENT_MINIMUM_TOKEN_REFRESH_INTERVAL = Duration.ofMinutes(4);
    static final Duration MAX_DURATION = Duration.ofMillis(Integer.MAX_VALUE);
    static final Duration MIN_DURATION = Duration.ofMillis(Integer.MIN_VALUE);
    static final int DEFAULT_CONNECTION_BUFFER_SIZE = 64 * 1024;
    static final int PING_INTERVAL_SECONDS = 30;
	
    // Listener should reconnect after 0, 1, 2, 5, 10, 30 seconds backoff delay
    static final Duration[] CONNECTION_DELAY_INTERVALS = { Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30) };
}
