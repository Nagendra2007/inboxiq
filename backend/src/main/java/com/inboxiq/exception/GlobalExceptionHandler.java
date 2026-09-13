package com.inboxiq.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central error handler. Nothing here ever writes a stack trace, exception
 * class name, SQL, or raw upstream (Google/OpenRouter) error body into the
 * HTTP response — those are logged server-side only, at a level that never
 * includes request/response bodies (see application.yml logging section).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("API error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        } else {
            log.warn("API error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        }
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = body("VALIDATION_ERROR", "One or more fields are invalid.");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body("VALIDATION_ERROR", "One or more fields are invalid."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        // Full detail goes to the server log only; the client gets a generic,
        // safe message. This is the last line of defense against ever leaking
        // a stack trace or internal detail to the browser.
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", "Something went wrong. Please try again."));
    }

    private Map<String, Object> body(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", code);
        body.put("message", message);
        return body;
    }
}
