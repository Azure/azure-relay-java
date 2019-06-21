package com.microsoft.azure.relay;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class TestUtil {
	public static final String CONNECTION_STRING_ENV_VARIABLE_NAME = "RELAY_CONNECTION_STRING";
	public static final RelayConnectionStringBuilder CONNECTION_STRING_BUILDER = new RelayConnectionStringBuilder(System.getenv(CONNECTION_STRING_ENV_VARIABLE_NAME));
	public static final URI RELAY_NAMESPACE_URI = CONNECTION_STRING_BUILDER.getEndpoint();
	public static final String ENTITY_PATH = CONNECTION_STRING_BUILDER.getEntityPath();
	public static final String KEY_NAME = CONNECTION_STRING_BUILDER.getSharedAccessKeyName();
	public static final String KEY = CONNECTION_STRING_BUILDER.getSharedAccessKey();
	
	static byte[] concatByteArrays(byte[]... arrays) {
		int totalSize = 0;
        for (byte[] array : arrays) {
            totalSize += array.length;
        }
        
        byte[] result = new byte[totalSize];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
    
    static byte[] byteBufferToArray(ByteBuffer buffer) {
        return Arrays.copyOfRange(buffer.array(), buffer.arrayOffset() + buffer.position(),
                buffer.remaining() + buffer.position() + buffer.arrayOffset());
    }
}
