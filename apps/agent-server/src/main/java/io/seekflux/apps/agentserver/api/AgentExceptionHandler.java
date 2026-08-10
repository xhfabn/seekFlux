package io.seekflux.apps.agentserver.api;

import io.seekflux.apps.agentserver.runtime.AgentSessionBusyException;
import io.seekflux.apps.agentserver.runtime.DuplicateAgentRequestException;
import io.seekflux.agent.domain.ConstraintVersionConflictException;
import io.seekflux.platform.agentruntime.session.AgentSessionStateConflictException;
import io.seekflux.platform.agentruntime.execution.AgentExecutionFencedException;
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
public class AgentExceptionHandler {

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(AgentSessionBusyException.class)
    public Map<String, Object> busy(AgentSessionBusyException error) {
        return error("AGENT_SESSION_BUSY", error.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(DuplicateAgentRequestException.class)
    public Map<String, Object> duplicate(DuplicateAgentRequestException error) {
        return error("DUPLICATE_AGENT_REQUEST", error.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler({ConstraintVersionConflictException.class, AgentSessionStateConflictException.class})
    public Map<String, Object> constraintConflict(RuntimeException error) {
        return error("AGENT_CONSTRAINT_VERSION_CONFLICT", error.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(AgentExecutionFencedException.class)
    public Map<String, Object> fenced(AgentExecutionFencedException error) {
        return error("AGENT_EXECUTION_FENCED", error.getMessage());
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
        String message = error.getMessage() == null ? "request validation failed" : error.getMessage();
        return error("INVALID_AGENT_REQUEST", message);
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(IllegalStateException.class)
    public Map<String, Object> unavailable(IllegalStateException error) {
        return error("AGENT_RUNTIME_UNAVAILABLE", error.getMessage());
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of(
                "code", code,
                "message", message,
                "timestamp", Instant.now().toString());
    }
}
