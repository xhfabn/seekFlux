package io.seekflux.apps.agentserver.interfaces.rest;

import io.seekflux.platform.agentruntime.application.command.CapabilityRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityChange;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityUpdateResult;
import io.seekflux.platform.agentruntime.domain.service.capability.CapabilityResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequestMapping("/v1/agent/sessions")
public final class AgentCapabilityController {

    private final AgentSessionStore sessions;
    private final Map<String, AgentDefinition> definitions;
    private final CapabilityResolver resolver;
    private final Clock clock;

    public AgentCapabilityController(
            AgentSessionStore sessions,
            @Qualifier("seekFluxAgentDefinitions") Map<String, AgentDefinition> definitions,
            CapabilityResolver resolver,
            Clock agentClock) {
        this.sessions = sessions;
        this.definitions = Map.copyOf(definitions);
        this.resolver = resolver;
        this.clock = agentClock;
    }

    @GetMapping("/{sessionId}/capabilities")
    public CapabilityActivationState get(
            @PathVariable @NotBlank @Size(max = 128) String sessionId) {
        var session = sessions.restoreFresh(sessionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "agent session was not found"));
        AgentDefinition definition = definitions.get(session.agentId());
        if (definition == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "agent definition is unavailable");
        }
        return resolver.materialize(session.capabilityState(), definition);
    }

    @PutMapping("/{sessionId}/capabilities")
    public CapabilityActivationState update(
            @RequestHeader("X-User-Id") @NotBlank @Size(max = 128) String actor,
            @PathVariable @NotBlank @Size(max = 128) String sessionId,
            @Valid @RequestBody CapabilityUpdateRequest request) {
        var session = sessions.restoreFresh(sessionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "agent session was not found"));
        AgentDefinition definition = definitions.get(session.agentId());
        if (definition == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "agent definition is unavailable");
        }
        CapabilityChange change = new CapabilityChange(
                1,
                request.operationId(),
                request.baseVersion(),
                request.activateSkills(),
                request.deactivateSkills(),
                request.activateToolGroups(),
                request.deactivateToolGroups());
        CapabilityActivationState current = resolver.materialize(
                session.capabilityState(), definition);
        if (current.version() != request.baseVersion()) {
            return result(sessions.updateCapabilities(
                    sessionId, change, null, actor, clock.instant()));
        }
        CapabilityActivationState candidate;
        try {
            candidate = change.apply(current);
            resolver.resolve(definition, candidate, CapabilityRequest.NONE);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getMessage());
        }
        CapabilityUpdateResult result = sessions.updateCapabilities(
                sessionId, change, candidate, actor, clock.instant());
        return result(result);
    }

    private static CapabilityActivationState result(CapabilityUpdateResult result) {
        return switch (result.status()) {
            case UPDATED, DUPLICATE -> result.state();
            case NOT_FOUND -> throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "agent session was not found");
            case BUSY -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "agent session cannot change capabilities while active");
            case CONFLICT -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "capability activation version conflict");
        };
    }

    public record CapabilityUpdateRequest(
            @NotBlank @Size(max = 128) String operationId,
            @NotNull @Min(0) Long baseVersion,
            @Size(max = 64) Set<@NotBlank @Size(max = 128) String> activateSkills,
            @Size(max = 64) Set<@NotBlank @Size(max = 128) String> deactivateSkills,
            @Size(max = 64) Set<@NotBlank @Size(max = 128) String> activateToolGroups,
            @Size(max = 64) Set<@NotBlank @Size(max = 128) String> deactivateToolGroups) {

        public CapabilityUpdateRequest {
            activateSkills = activateSkills == null ? Set.of() : Set.copyOf(activateSkills);
            deactivateSkills = deactivateSkills == null ? Set.of() : Set.copyOf(deactivateSkills);
            activateToolGroups = activateToolGroups == null ? Set.of() : Set.copyOf(activateToolGroups);
            deactivateToolGroups = deactivateToolGroups == null ? Set.of() : Set.copyOf(deactivateToolGroups);
        }
    }
}
