package io.seekflux.apps.onlineserver.api.error;

import io.seekflux.core.feature.InteractionIdempotencyConflictException;
import io.seekflux.core.search.api.SearchUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

@RestControllerAdvice(basePackages = "io.seekflux.apps.onlineserver.api")
public final class OnlineExceptionHandler {

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(InteractionIdempotencyConflictException.class)
    public ApiError interactionConflict(InteractionIdempotencyConflictException error) {
        return error("INTERACTION_IDEMPOTENCY_CONFLICT", error.getMessage());
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(SearchUnavailableException.class)
    public ApiError searchUnavailable(SearchUnavailableException error) {
        return error("SEARCH_UNAVAILABLE", error.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({
            IllegalArgumentException.class,
            jakarta.validation.ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    public ApiError badRequest(Exception error, HttpServletRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        String code = handler instanceof HandlerMethod method
                ? switch (method.getBeanType().getPackageName()) {
                    case "io.seekflux.apps.onlineserver.api.search" -> "INVALID_SEARCH_REQUEST";
                    case "io.seekflux.apps.onlineserver.api.interaction" -> "INVALID_INTERACTION_REQUEST";
                    case "io.seekflux.apps.onlineserver.api.recommendation" -> "INVALID_RECOMMENDATION_REQUEST";
                    case "io.seekflux.apps.onlineserver.api.interest" -> "INVALID_INTEREST_REQUEST";
                    case "io.seekflux.apps.onlineserver.api.feature" -> "INVALID_FEATURE_REQUEST";
                    default -> "INVALID_REQUEST";
                }
                : "INVALID_REQUEST";
        String message = error.getMessage() == null ? "request validation failed" : error.getMessage();
        return error(code, message);
    }

    private static ApiError error(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}
