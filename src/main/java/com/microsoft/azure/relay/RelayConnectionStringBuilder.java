package com.microsoft.azure.relay;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;

class RelayConnectionStringBuilder {
    static final String ENDPOINT_CONFIG_NAME = "Endpoint";
    static final String ENTITY_PATH_CONFIG_NAME = "EntityPath";
    static final String OPERATION_TIMEOUT_CONFIG_NAME = "OperationTimeout";
    static final String SHARED_ACCESS_KEYNAME_CONFIG_NAME = "SharedAccessKeyName";
    static final String SHARED_ACCESS_KEY_CONFIG_NAME = "SharedAccessKey";
    static final String SHARED_ACCESS_SIGNATURE_CONFIG_NAME = "SharedAccessSignature";
    static final char KEY_VALUE_SEPARATOR = '=';
    static final char KEY_VALUE_PAIR_DELIMITER = ';';
    
    // The Relay namespace address
    private URI endpoint;
    // How long operations have to complete before timing out. The default value is one minute.
    private Duration operationTimeout;
    // The entity path for hybridconnection
    private String entityPath;
    // The name of the shared access key
    private String sharedAccessKeyName;
    // The shared access key for the connection authentication
    private String sharedAccessKey;
    // Returns the configured SAS token
    private String sharedAccessSignature;

	// Initializes a new instance of the <see cref="RelayConnectionStringBuilder" /> class.</summary>
    protected RelayConnectionStringBuilder()
    {
        this.operationTimeout = RelayConstants.DEFAULT_OPERATION_TIMEOUT;
    }
    
    /// <summary>
    /// Initializes a new instance of a <see cref="RelayConnectionStringBuilder" /> with a specified existing connection String.
    /// </summary> 
    /// <param name="connectionString">The connection String, which can be obtained from the Azure Management Portal.</param>
    /// <exception cref="ArgumentNullException">Thrown if connectionString is null or empty.</exception>
    /// <exception cref="ArgumentOutOfRangeException">
    /// Thrown if <see cref="RelayConnectionStringBuilder.OperationTimeout"/> is a non-positive <see cref="TimeSpan"/>.
    /// </exception>
    /// <exception cref="System.ArgumentException">
    /// Thrown if a key value pair is missing either a key or a value.
    /// Thrown if <see cref="RelayConnectionStringBuilder.Endpoint"/> is specified but is not a valid absolute <see cref="Uri"/>.
    /// Thrown if <see cref="RelayConnectionStringBuilder.OperationTimeout"/> is specified but is not a valid <see cref="TimeSpan"/> format.
    /// Thrown if an unsupported key name is specified.
    /// </exception>
    protected RelayConnectionStringBuilder(String connectionString) {
    	this();
        if (StringUtil.isNullOrEmpty(connectionString))
        {
        	throw new IllegalArgumentException("connection cannot be null or empty");
        }

        this.parseConnectionString(connectionString);
    }

    protected URI getEndpoint() {
		return endpoint;
	}

	protected void setEndpoint(URI value) {
        if (value == null)
        {
        	throw new IllegalArgumentException("the supplied endpoint endpoint cannot be null");
        }
        else if (!value.isAbsolute())
        {
        	throw new IllegalArgumentException("the supplied endpoint must be n bsolute uri");
        }
        this.endpoint = value;
	}

	protected Duration getOperationTimeout() {
		return operationTimeout;
	}

	protected void setOperationTimeout(Duration value) {
		if (value.isNegative())
			throw new IllegalArgumentException("the timeout duration cannot be negative");
        this.operationTimeout = value;
	}

	protected String getEntityPath() {
		return entityPath;
	}

	protected void setEntityPath(String entityPath) {
		this.entityPath = entityPath;
	}

	protected String getSharedAccessKeyName() {
		return sharedAccessKeyName;
	}

	protected void setSharedAccessKeyName(String sharedAccessKeyName) {
		this.sharedAccessKeyName = sharedAccessKeyName;
	}

	protected String getSharedAccessKey() {
		return sharedAccessKey;
	}

	protected void setSharedAccessKey(String sharedAccessKey) {
		this.sharedAccessKey = sharedAccessKey;
	}

	protected String getSharedAccessSignature() {
		return sharedAccessSignature;
	}

	protected void setSharedAccessSignature(String sharedAccessSignature) {
		this.sharedAccessSignature = sharedAccessSignature;
	}

    // Creates a connectionString that represents the current object
	@Override
	public String toString() {
        this.validate();
        StringBuilder connectionStringBuilder = new StringBuilder(200);

        connectionStringBuilder.append(ENDPOINT_CONFIG_NAME + KEY_VALUE_SEPARATOR + this.endpoint + KEY_VALUE_PAIR_DELIMITER);

        if (!StringUtil.isNullOrWhiteSpace(this.entityPath)) {
            connectionStringBuilder.append(ENTITY_PATH_CONFIG_NAME + KEY_VALUE_SEPARATOR + this.entityPath + KEY_VALUE_PAIR_DELIMITER);
        }

        if (!StringUtil.isNullOrWhiteSpace(this.sharedAccessKeyName))
        {
            connectionStringBuilder.append(SHARED_ACCESS_KEYNAME_CONFIG_NAME + KEY_VALUE_SEPARATOR + this.sharedAccessKeyName + KEY_VALUE_PAIR_DELIMITER);
        }

        if (!StringUtil.isNullOrWhiteSpace(this.sharedAccessKey))
        {
            connectionStringBuilder.append(SHARED_ACCESS_KEY_CONFIG_NAME + KEY_VALUE_SEPARATOR + this.sharedAccessKey + KEY_VALUE_PAIR_DELIMITER);
        }

        if (!StringUtil.isNullOrWhiteSpace(this.sharedAccessSignature))
        {
            connectionStringBuilder.append(SHARED_ACCESS_SIGNATURE_CONFIG_NAME + KEY_VALUE_SEPARATOR + this.sharedAccessSignature + KEY_VALUE_PAIR_DELIMITER);
        }

        if (this.operationTimeout != RelayConstants.DEFAULT_OPERATION_TIMEOUT)
        {
            connectionStringBuilder.append(OPERATION_TIMEOUT_CONFIG_NAME + KEY_VALUE_SEPARATOR + this.operationTimeout.toString() + KEY_VALUE_PAIR_DELIMITER);
        }

        return connectionStringBuilder.toString();
    }

    protected TokenProvider createTokenProvider() {
        TokenProvider tokenProvider = null;
        
        if (!StringUtil.isNullOrEmpty(this.sharedAccessSignature)) {
            tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(this.sharedAccessSignature);
        }
        else if (!StringUtil.isNullOrEmpty(this.sharedAccessKeyName) && !StringUtil.isNullOrEmpty(this.sharedAccessKey)) {
            tokenProvider = TokenProvider.createSharedAccessSignatureTokenProvider(this.sharedAccessKeyName, this.sharedAccessKey);
        }
        else {
        	throw new IllegalArgumentException("need to supply sharedAccessSignature or both sharedAccessKeyName and sharedAccessKey");
        }

        return tokenProvider;
    }

    protected void validate() {
        if (this.endpoint == null)
        {
            throw new IllegalArgumentException("provided endpoint cannot be null");
        }

        // if one supplied SharedAccessKeyName, they need to supply SharedAccessKey, and vise versa
        // if SharedAccessSignature is specified, Neither SasKey nor SasKeyName should not be specified
        boolean hasSharedAccessKeyName = !StringUtil.isNullOrWhiteSpace(this.sharedAccessKeyName);
        boolean hasSharedAccessKey = !StringUtil.isNullOrWhiteSpace(this.sharedAccessKey);
        boolean hasSharedAccessSignature = !StringUtil.isNullOrWhiteSpace(this.sharedAccessSignature);
        if (hasSharedAccessSignature)
        {
            if (hasSharedAccessKeyName)
            {
            	throw new IllegalArgumentException("sharedAccessKeyName should not be supplied when sharedAccessSignture is supplied.");
            }

            if (hasSharedAccessKey)
            {
            	throw new IllegalArgumentException("sharedAccessKey should not be supplied when sharedAccessSignture is supplied.");
            }
        }
        else if ((hasSharedAccessKeyName && !hasSharedAccessKey) || (!hasSharedAccessKeyName && hasSharedAccessKey))
        {
        	throw new IllegalArgumentException("sharedAccessKeyName and sharedAccessKey should be both supplied when sharedAccessSignture is not supplied.");
        }
    }

    private void parseConnectionString(String connectionString) {
        // First split into strings based on ';'
        String[] keyValuePairs = connectionString.split(String.valueOf(KEY_VALUE_PAIR_DELIMITER));
        for (String keyValuePair : keyValuePairs)
        {
        	if (StringUtil.isNullOrEmpty(keyValuePair))
        		continue;
        	
            // Now split based on the *first* '='
            String[] keyAndValue = keyValuePair.split(String.valueOf(KEY_VALUE_SEPARATOR), 2);
            if (keyAndValue.length != 2)
            {
            	throw new IllegalArgumentException("invalid key value pair in connection string");
            }
            String key = keyAndValue[0];
            String value = keyAndValue[1];
            
            if (key.equals(ENDPOINT_CONFIG_NAME)) {
                URI endpoint;
                try {
                	endpoint = new URI(value);
                } 
                catch (URISyntaxException e) {
                	throw new IllegalArgumentException("The following string cannot be used to build a valid URI: " + e.getInput());
                }
                if (!endpoint.isAbsolute()) {
                	throw new IllegalArgumentException("The following string must be a valid absolute URI: " + endpoint);
                }
                this.endpoint = endpoint;
            }
            else if (key.equalsIgnoreCase(ENTITY_PATH_CONFIG_NAME)) {
                this.entityPath = value;
            }
            else if (key.equalsIgnoreCase(SHARED_ACCESS_KEYNAME_CONFIG_NAME)) {
                this.sharedAccessKeyName = value;
            }
            else if (key.equalsIgnoreCase(SHARED_ACCESS_KEY_CONFIG_NAME)) {
                this.sharedAccessKey = value;
            }
            else if (key.equalsIgnoreCase(SHARED_ACCESS_SIGNATURE_CONFIG_NAME)) {
                this.sharedAccessSignature = value;
            }
            else if (key.equalsIgnoreCase(OPERATION_TIMEOUT_CONFIG_NAME)) {
            	
                try {
                	Duration timeValue = Duration.parse(value);
                	this.operationTimeout = timeValue;
                } catch (DateTimeParseException e) {
                	throw new DateTimeParseException(value + " cannot be parsed into a valid duration.", e.getParsedString(), e.getErrorIndex());
                }
            }
            else {
                throw new IllegalArgumentException("the following is not a valid field for connection string: " + key);
            }
        }
    }
}

