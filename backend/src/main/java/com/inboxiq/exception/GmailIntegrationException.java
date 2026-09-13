package com.inboxiq.exception;

import org.springframework.http.HttpStatus;

/**
 * Wraps any failure talking to the Gmail API (expired/revoked auth, quota
 * exceeded, network failure, malformed response) into one exception type
 * carrying a status appropriate for the frontend to act on, without ever
 * exposing the underlying Google API exception's raw message (which can
 * include request details) directly to the client.
 */
public class GmailIntegrationException extends ApiException {

    public GmailIntegrationException(HttpStatus status, String errorCode, String message, Throwable cause) {
        super(status, errorCode, message, cause);
    }

    public static GmailIntegrationException reauthRequired(Throwable cause) {
        return new GmailIntegrationException(HttpStatus.CONFLICT, "GMAIL_REAUTH_REQUIRED",
                "Gmail access has expired or been revoked. Please reconnect your Gmail account.", cause);
    }

    public static GmailIntegrationException quotaExceeded(Throwable cause) {
        return new GmailIntegrationException(HttpStatus.TOO_MANY_REQUESTS, "GMAIL_QUOTA_EXCEEDED",
                "Gmail API rate limit reached. Please try again shortly.", cause);
    }

    public static GmailIntegrationException unavailable(Throwable cause) {
        return new GmailIntegrationException(HttpStatus.BAD_GATEWAY, "GMAIL_UNAVAILABLE",
                "Could not reach Gmail right now. Please try again shortly.", cause);
    }
}
