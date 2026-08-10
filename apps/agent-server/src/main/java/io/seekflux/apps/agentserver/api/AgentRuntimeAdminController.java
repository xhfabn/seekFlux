package io.seekflux.apps.agentserver.api;

import io.seekflux.platform.agentruntime.llm.ShadowControl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/agent/runtime")
public final class AgentRuntimeAdminController {

    private final ShadowControl shadowControl;

    public AgentRuntimeAdminController(ShadowControl shadowControl) {
        this.shadowControl = shadowControl;
    }

    @GetMapping("/shadow")
    public ShadowControl.Settings shadow() {
        return shadowControl.current();
    }

    @PutMapping("/shadow")
    public ShadowControl.Settings updateShadow(@Valid @RequestBody ShadowUpdate request) {
        return shadowControl.update(request.enabled(), request.sampleRate());
    }

    public record ShadowUpdate(
            @NotNull Boolean enabled,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double sampleRate) {
    }
}
