package io.seekflux.apps.contentserver.api;

import io.seekflux.content.application.ContentConcurrencyException;
import io.seekflux.content.application.ContentNotFoundException;
import io.seekflux.content.domain.ContentStateException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ContentExceptionHandler {

    @ExceptionHandler(ContentNotFoundException.class)
    ResponseEntity<ApiError> notFound(ContentNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler({ContentStateException.class, ContentConcurrencyException.class})
    ResponseEntity<ApiError> conflict(RuntimeException exception) {
        return error(HttpStatus.CONFLICT, "CONTENT_STATE_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, WebExchangeBindException.class})
    ResponseEntity<ApiError> badRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Instant.now()));
    }
}
