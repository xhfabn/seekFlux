package io.seekflux.apps.agentserver.api;

import io.seekflux.agent.domain.ConstraintPatch;
import io.seekflux.agent.port.in.AgentRequestedMode;
import io.seekflux.agent.port.in.AgentSearchCommand;
import io.seekflux.agent.port.in.AgentSearchUseCase;
import io.seekflux.platform.agentruntime.router.Router;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/agent")
public class AgentSearchController {

    private final AgentSearchUseCase agentSearch;
    private final Router router;

    public AgentSearchController(AgentSearchUseCase agentSearch, Router router) {
        this.agentSearch = agentSearch;
        this.router = router;
    }

    @PostMapping("/search")
    public AgentSearchResponse search(@Valid @RequestBody AgentSearchRequest request) {
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString()
                : request.requestId().trim();
        String agentId = request.agentId() == null || request.agentId().isBlank()
                ? "search-assistant"
                : request.agentId().trim();
        boolean allowClarification = request.options() == null
                || request.options().allowClarification() == null
                || request.options().allowClarification();
        return AgentSearchResponse.from(agentSearch.search(new AgentSearchCommand(
                requestId,
                request.sessionId(),
                request.turnId(),
                agentId,
                request.query(),
                request.page() == null ? 0 : request.page(),
                request.size() == null ? 12 : request.size(),
                request.requiredTags() == null ? List.of() : request.requiredTags(),
                allowClarification,
                request.mode() == null ? AgentRequestedMode.AUTO : request.mode(),
                constraintPatch(request.constraintPatch()))));
    }

    private static ConstraintPatch constraintPatch(AgentSearchRequest.ConstraintPatchRequest patch) {
        if (patch == null) {
            return null;
        }
        return new ConstraintPatch(
                patch.baseVersion(),
                patch.replacementQuery(),
                patch.page(),
                patch.size(),
                patch.addRequiredTags() == null ? List.of() : patch.addRequiredTags(),
                patch.removeRequiredTags() == null ? List.of() : patch.removeRequiredTags());
    }

    @PostMapping("/sessions/{sessionId}:cancel")
    public Map<String, Object> cancel(
            @PathVariable("sessionId") @Size(min = 1, max = 128) String sessionId) {
        boolean cancelled = router.cancel(sessionId, false);
        return Map.of("sessionId", sessionId, "cancelled", cancelled);
    }
}
