package io.seekflux.apps.agentserver.interfaces.rest;

import io.seekflux.platform.agentruntime.application.api.WaitResumeDispatcher;
import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/agent/sessions")
@Validated
public class AgentWaitController {

    private final WaitResumeDispatcher dispatcher;
    private final AgentSessionStore sessions;
    private final Clock clock;

    public AgentWaitController(
            WaitResumeDispatcher dispatcher,
            AgentSessionStore sessions,
            Clock agentClock) {
        this.dispatcher = dispatcher;
        this.sessions = sessions;
        this.clock = agentClock;
    }

    @PostMapping("/{sessionId}/waits/{waitId}:resolve")
    public AgentWaitResponse resolve(
            @RequestHeader("X-User-Id") @NotBlank @Size(max = 128) String userId,
            @PathVariable @NotBlank @Size(max = 128) String sessionId,
            @PathVariable @NotBlank @Size(max = 128) String waitId,
            @Valid @RequestBody AgentWaitResolutionRequest request) {
        var session = sessions.restoreFresh(sessionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "agent session was not found"));
        var pending = sessions.waitState(session.sessionId(), waitId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "agent wait was not found"));
        if (!pending.waitId().equals(waitId)
                || !pending.requestId().equals(request.requestId())
                || !pending.turnId().equals(request.turnId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "wait resolution identity does not match");
        }
        WaitResolution resolution = new WaitResolution(
                1,
                request.resolutionId(),
                waitId,
                sessionId,
                request.requestId(),
                request.turnId(),
                request.outcome(),
                request.output(),
                request.errorCode(),
                userId,
                clock.instant());
        RouterResult routed = dispatcher.dispatch(resolution, PushEventPublisher.NOOP);
        if (routed.status() == RouterResult.Status.BUSY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "agent session is busy");
        }
        if (routed.status() == RouterResult.Status.DUPLICATE) {
            return new AgentWaitResponse("DUPLICATE", waitId, null, null, Map.of(), null);
        }
        if (routed.status() != RouterResult.Status.COMPLETED || routed.outcome() == null) {
            HttpStatus status = "WAIT_NOT_FOUND".equals(routed.reason())
                    ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            throw new ResponseStatusException(status, routed.reason());
        }
        var outcome = routed.outcome();
        return new AgentWaitResponse(
                "COMPLETED",
                waitId,
                outcome.state().name(),
                outcome.trace().agentRunId(),
                outcome.output(),
                outcome.fallbackReason());
    }

    public record AgentWaitResolutionRequest(
            @NotBlank @Size(max = 128) String resolutionId,
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 128) String turnId,
            @NotNull WaitResolution.Outcome outcome,
            Map<String, Object> output,
            @Size(max = 128) String errorCode) {

        public AgentWaitResolutionRequest {
            output = output == null ? Map.of() : Map.copyOf(output);
        }
    }

    public record AgentWaitResponse(
            String status,
            String waitId,
            String terminalState,
            String agentRunId,
            Map<String, Object> output,
            String errorCode) {
    }
}
