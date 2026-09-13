package com.inboxiq.exception;

import org.springframework.http.HttpStatus;

/** Any failure calling the configured LLM provider, or parsing its response. */
public class AiServiceException extends ApiException {

    /** The provider's own explanation, when it gave one (shown to the admin by "Test connection"). */
    private final String providerDetail;

    public AiServiceException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public AiServiceException(String message) {
        super(HttpStatus.BAD_GATEWAY, "AI_SERVICE_ERROR", message);
        this.providerDetail = null;
    }

    public AiServiceException(String message, String providerDetail, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "AI_SERVICE_ERROR", message, cause);
        this.providerDetail = providerDetail;
    }

    public String getProviderDetail() { return providerDetail; }
}
