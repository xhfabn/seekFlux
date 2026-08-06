package io.seekflux.apps.onlineserver.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public final class SearchExceptionHandler {

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({
            IllegalArgumentException.class,
            jakarta.validation.ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            WebExchangeBindException.class,
            ServerWebInputException.class
    })
    public Map<String, Object> badRequest(Exception error, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String code = path.startsWith("/v1/search")
                ? "INVALID_SEARCH_REQUEST"
                : "INVALID_RECOMMENDATION_REQUEST";
        String message = error.getMessage() == null ? "request validation failed" : error.getMessage();
        return Map.of("code", code, "message", message,
                "timestamp", Instant.now().toString());
    }
}
