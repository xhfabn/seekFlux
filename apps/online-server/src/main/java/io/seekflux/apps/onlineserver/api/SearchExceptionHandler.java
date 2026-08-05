package io.seekflux.apps.onlineserver.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class SearchExceptionHandler {

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({IllegalArgumentException.class, jakarta.validation.ConstraintViolationException.class})
    public Map<String, Object> badRequest(Exception error) {
        return Map.of("code", "INVALID_SEARCH_REQUEST", "message", error.getMessage(),
                "timestamp", Instant.now().toString());
    }
}
