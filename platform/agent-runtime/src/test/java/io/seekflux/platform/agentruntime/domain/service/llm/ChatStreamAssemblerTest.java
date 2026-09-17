package io.seekflux.platform.agentruntime.domain.service.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ChatChunk;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;
import org.junit.jupiter.api.Test;

class ChatStreamAssemblerTest {

    @Test
    void assemblesTextReasoningUsageAndFragmentedToolCallsByIndex() {
        ChatStreamAssembler assembler = new ChatStreamAssembler();

        assembler.accept(new ChatChunk(0, "{\"action\":", "plan ", null,
                LlmUsage.UNMEASURED, null, false));
        assembler.accept(new ChatChunk(1, "\"call_tools\"}", "search", null,
                new LlmUsage(12, 4, 16, 0, true, 3, 2), "tool_calls", false));
        assembler.accept(new ChatChunk(2, "", "",
                new ChatChunk.ToolCallDelta(1, "call-b", "search_", "{\"q\":", false),
                LlmUsage.UNMEASURED, null, false));
        assembler.accept(new ChatChunk(3, "", "",
                new ChatChunk.ToolCallDelta(0, "call-a", "search_direct", "{\"q\":\"a\"}", true),
                LlmUsage.UNMEASURED, null, false));
        assembler.accept(new ChatChunk(4, "", "",
                new ChatChunk.ToolCallDelta(1, "", "filtered", "\"b\"}", true),
                LlmUsage.UNMEASURED, null, false));
        assembler.accept(new ChatChunk(5, "", "", null,
                LlmUsage.UNMEASURED, "done", true));

        ChatStreamAssembler.Assembly result = assembler.finish();
        assertEquals("{\"action\":\"call_tools\"}", result.content());
        assertEquals("plan search", result.reasoning());
        assertEquals(3, result.usage().cachedInputTokens());
        assertEquals(2, result.usage().reasoningTokens());
        assertEquals("search_direct", result.toolCalls().get(0).name());
        assertEquals("search_filtered", result.toolCalls().get(1).name());
        assertEquals("{\"q\":\"b\"}", result.toolCalls().get(1).argumentsJson());
        assertTrue(result.toolCalls().stream().allMatch(ChatStreamAssembler.ToolCall::argumentsComplete));
    }

    @Test
    void rejectsSequenceGapsAndChunksAfterTerminal() {
        ChatStreamAssembler assembler = new ChatStreamAssembler();
        assertThrows(IllegalStateException.class, () -> assembler.accept(
                new ChatChunk(1, "late", "", null, LlmUsage.UNMEASURED, null, false)));

        ChatStreamAssembler completed = new ChatStreamAssembler();
        completed.accept(new ChatChunk(0, "ok", "", null,
                LlmUsage.UNMEASURED, "done", true));
        assertThrows(IllegalStateException.class, () -> completed.accept(
                new ChatChunk(1, "late", "", null, LlmUsage.UNMEASURED, null, false)));
    }
}
