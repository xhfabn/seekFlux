package io.seekflux.apps.onlineserver.api;

import io.seekflux.search.port.in.SearchUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public final class SearchExceptionHandler {

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(SearchUnavailableException.class)
    public Map<String, Object> searchUnavailable(SearchUnavailableException error) {
        return Map.of(
                "code", "SEARCH_UNAVAILABLE",
                "message", error.getMessage(),
                "timestamp", Instant.now().toString());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({
            IllegalArgumentException.class,
            jakarta.validation.ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    public Map<String, Object> badRequest(Exception error, HttpServletRequest request) {
        String path = request.getRequestURI();
        String code = path.startsWith("/v1/search")
                ? "INVALID_SEARCH_REQUEST"
                : "INVALID_RECOMMENDATION_REQUEST";
        String message = error.getMessage() == null ? "request validation failed" : error.getMessage();
        return Map.of("code", code, "message", message,
                "timestamp", Instant.now().toString());
    }
}
