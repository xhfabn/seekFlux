package io.seekflux.platform.agenteval;

import io.seekflux.platform.agenteval.runtime.*;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.tool.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CustomToolContractTest {
    @Test void businessToolGetsDefaultFaultAndCancellationCasesWithoutInjectionCode() {
        var created = new AtomicInteger();
        var calls = new AtomicInteger();
        var suite = ToolContractSuite.create("convert", Map.of("text", "hello"), Map.of("result", "HELLO"));
        var target = ToolContractSuite.target("custom", "1", () -> {
            created.incrementAndGet();
            return new AgentTool() {
                public String name() { return "convert"; }
                public Effect effect() { return Effect.READ_ONLY; }
                public AgentToolSchema schema() { return new AgentToolSchema("1", Map.of("text", AgentToolParameter.requiredString(100))); }
                public AgentToolResult execute(AgentToolContext context) {
                    calls.incrementAndGet();
                    return AgentToolResult.success(Map.of("result", context.arguments().get("text").toString().toUpperCase(java.util.Locale.ROOT)), null);
                }
            };
        });
        var report = EvalCli.runner().run(suite, target);
        assertTrue(report.passed(), () -> EvalJson.pretty(report));
        assertEquals(4, created.get());
        assertEquals(2, calls.get()); // success and AFTER_TOOL; no body entry on failure/cancel before tool
    }
}

