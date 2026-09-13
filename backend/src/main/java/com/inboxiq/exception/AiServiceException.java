package com.inboxiq.exception;

import org.springframework.http.HttpStatus;

/** Any failure calling the configured LLM provider, or parsing its response. */
public class AiServiceException extends ApiException {
    public AiServiceException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "AI_SERVICE_ERROR", message, cause);
    }
    public AiServiceException(String message) {
        super(HttpStatus.BAD_GATEWAY, "AI_SERVICE_ERROR", message);
    }
}
