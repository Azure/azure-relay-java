// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.relay;

enum WebSocketCloseStatus {
	NORMAL_CLOSURE, ENDPOINT_UNAVAILABLE, PROTOCOL_ERROR, INVALID_MESSAGE_TYPE, EMPTY, INVALID_PAYLOAD_DATA,
	POLICY_VIOLATION, MESSAGE_TOO_BIG, MANDATORY_EXTENSION, INTERNAL_SERVER_ERROR
}
