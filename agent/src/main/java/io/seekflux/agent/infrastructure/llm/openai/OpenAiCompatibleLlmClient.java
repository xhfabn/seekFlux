package io.seekflux.agent.infrastructure.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ContextMessage;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.LlmCallResult;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ChatChunk;
import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextEventRecorder;
import io.seekflux.platform.agentruntime.application.spi.business.output.OutputGuardPolicy;
import io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException;
import io.seekflux.platform.agentruntime.domain.exception.AgentModelOutputException;
import io.seekflux.platform.agentruntime.domain.exception.ContextOverflowException;
import io.seekflux.platform.agentruntime.domain.exception.LlmStreamException;
import io.seekflux.platform.agentruntime.domain.model.context.ContextEvent;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.message.AgentAssistantContent;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;
import io.seekflux.platform.agentruntime.domain.service.llm.ChatStreamAssembler;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.function.Consumer;

public final class OpenAiCompatibleLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final String version;
    private final double inputUsdPerMillionTokens;
    private final double outputUsdPerMillionTokens;
    private final OutputGuardPolicy outputGuardPolicy;
    private final ContextEventRecorder contextEvents;
    private final Clock clock;

    public OpenAiCompatibleLlmClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI endpoint,
            String apiKey,
            String model,
            Duration timeout) {
        this(httpClient, objectMapper, endpoint, apiKey, model, timeout, 0, 0);
    }

    public OpenAiCompatibleLlmClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI endpoint,
            String apiKey,
            String model,
            Duration timeout,
            double inputUsdPerMillionTokens,
            double outputUsdPerMillionTokens) {
        this(httpClient, objectMapper, endpoint, apiKey, model, timeout,
                inputUsdPerMillionTokens, outputUsdPerMillionTokens,
                OutputGuardPolicy.REPAIR_THEN_DEGRADE, ContextEventRecorder.NOOP,
                Clock.systemUTC());
    }

    public OpenAiCompatibleLlmClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI endpoint,
            String apiKey,
            String model,
            Duration timeout,
            double inputUsdPerMillionTokens,
            double outputUsdPerMillionTokens,
            OutputGuardPolicy outputGuardPolicy,
            ContextEventRecorder contextEvents,
            Clock clock) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = requireText(model, "LLM model");
        this.timeout = timeout;
        this.version = "openai-compatible:" + this.model + ":v2@" + endpoint.getHost();
        this.inputUsdPerMillionTokens = Math.max(0, inputUsdPerMillionTokens);
        this.outputUsdPerMillionTokens = Math.max(0, outputUsdPerMillionTokens);
        this.outputGuardPolicy = outputGuardPolicy == null
                ? OutputGuardPolicy.REPAIR_THEN_DEGRADE : outputGuardPolicy;
        this.contextEvents = contextEvents == null ? ContextEventRecorder.NOOP : contextEvents;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public AgentDecision chat(AssembledContext context) {
        return chatWithUsage(context).decision();
    }

    @Override
    public LlmCallResult chatWithUsage(AssembledContext context) {
        return chatWithUsage(context, new CancellationToken());
    }

    @Override
    public LlmCallResult chatWithUsage(
            AssembledContext context,
            CancellationToken cancellationToken) {
        cancellationToken.throwIfCancelled();
        Duration requestTimeout = context.decisionContext().remaining().compareTo(timeout) < 0
                ? context.decisionContext().remaining()
                : timeout;
        List<Map<String, Object>> messages = new java.util.ArrayList<>(
                context.messages().stream().map(OpenAiCompatibleLlmClient::message).toList());
        try {
            ProviderResult provider = send(context, messages, requestTimeout, cancellationToken);
            LlmUsage totalUsage = provider.usage();
            int repairs = 0;
            while (true) {
                cancellationToken.throwIfCancelled();
                try {
                    AgentDecision decision = parseDecision(
                            provider.assistant().decisionContent(), context);
                    record(ContextEvent.Type.OUTPUT_ACCEPTED, context,
                            repairs == 0 ? "VALID_FIRST_OUTPUT" : "REPAIRED_OUTPUT");
                    return new LlmCallResult(
                            decision,
                            totalUsage,
                            new AgentAssistantContent(
                                    provider.assistant().decisionContent(),
                                    provider.assistant().reasoningContent(),
                                    false));
                } catch (AgentCancellationException cancelled) {
                    throw cancelled;
                } catch (RuntimeException invalidOutput) {
                    if (repairs < outputGuardPolicy.maxRepairAttempts()) {
                        repairs++;
                        record(ContextEvent.Type.OUTPUT_REPAIR, context, "INVALID_STRUCTURED_OUTPUT");
                        messages = repairMessages(
                                context,
                                provider.assistant().decisionContent(),
                                invalidOutput);
                        provider = send(context, messages, requestTimeout, cancellationToken);
                        totalUsage = totalUsage.plus(provider.usage());
                        continue;
                    }
                    if (outputGuardPolicy.exhaustedAction()
                            == OutputGuardPolicy.ExhaustedAction.DEGRADE) {
                        record(ContextEvent.Type.OUTPUT_DEGRADED, context, "REPAIR_EXHAUSTED");
                        return new LlmCallResult(
                                new AgentDecision.Fallback("LLM_OUTPUT_INVALID"),
                                totalUsage,
                                AgentAssistantContent.EMPTY);
                    }
                    record(ContextEvent.Type.OUTPUT_EXHAUSTED, context, "REPAIR_EXHAUSTED");
                    throw new AgentModelOutputException(
                            "LLM_OUTPUT_GUARD_EXHAUSTED", invalidOutput);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationCause cause = cancellationToken.cause();
            if (cause != null) {
                throw new AgentCancellationException(cause);
            }
            throw new IllegalStateException("LLM provider call was interrupted", interrupted);
        } catch (IOException error) {
            throw new IllegalStateException("LLM provider call failed", error);
        }
    }

    @Override
    public LlmCallResult streamWithUsage(
            AssembledContext context,
            CancellationToken cancellationToken,
            Consumer<ChatChunk> chunks) {
        cancellationToken.throwIfCancelled();
        Duration requestTimeout = context.decisionContext().remaining().compareTo(timeout) < 0
                ? context.decisionContext().remaining() : timeout;
        List<Map<String, Object>> messages = new java.util.ArrayList<>(
                context.messages().stream().map(OpenAiCompatibleLlmClient::message).toList());
        try {
            ProviderResult provider = null;
            int preOutputRetries = 0;
            while (provider == null) {
                PreOutputBuffer forwarding = new PreOutputBuffer(chunks);
                try {
                    provider = sendStream(
                            context, messages, requestTimeout, cancellationToken, forwarding);
                } catch (LlmStreamException failure) {
                    if (!failure.outputStarted() && preOutputRetries++ < 1) {
                        continue;
                    }
                    throw failure;
                } catch (HttpTimeoutException timeoutFailure) {
                    if (preOutputRetries++ < 1) {
                        continue;
                    }
                    throw new LlmStreamException(
                            "LLM_FIRST_CHUNK_TIMEOUT", false, timeoutFailure);
                } catch (IOException transportFailure) {
                    if (preOutputRetries++ < 1) {
                        continue;
                    }
                    throw transportFailure;
                }
            }
            return guard(context, messages, provider, cancellationToken, requestTimeout, false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationCause cause = cancellationToken.cause();
            if (cause != null) {
                throw new AgentCancellationException(cause);
            }
            throw new LlmStreamException("LLM_STREAM_INTERRUPTED", false, interrupted);
        } catch (IOException error) {
            throw new LlmStreamException("LLM_STREAM_FAILED_BEFORE_OUTPUT", false, error);
        }
    }

    private LlmCallResult guard(
            AssembledContext context,
            List<Map<String, Object>> messages,
            ProviderResult initial,
            CancellationToken cancellationToken,
            Duration requestTimeout,
            boolean allowRepair) throws IOException, InterruptedException {
        ProviderResult provider = initial;
        LlmUsage totalUsage = provider.usage();
        int repairs = 0;
        while (true) {
            cancellationToken.throwIfCancelled();
            try {
                AgentDecision decision = parseDecision(
                        provider.assistant().decisionContent(), context);
                record(ContextEvent.Type.OUTPUT_ACCEPTED, context,
                        repairs == 0 ? "VALID_FIRST_OUTPUT" : "REPAIRED_OUTPUT");
                return new LlmCallResult(
                        decision,
                        totalUsage,
                        new AgentAssistantContent(
                                provider.assistant().decisionContent(),
                                provider.assistant().reasoningContent(),
                                false));
            } catch (AgentCancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException invalidOutput) {
                if (allowRepair && repairs < outputGuardPolicy.maxRepairAttempts()) {
                    repairs++;
                    record(ContextEvent.Type.OUTPUT_REPAIR, context, "INVALID_STRUCTURED_OUTPUT");
                    messages = repairMessages(
                            context, provider.assistant().decisionContent(), invalidOutput);
                    provider = send(context, messages, requestTimeout, cancellationToken);
                    totalUsage = totalUsage.plus(provider.usage());
                    continue;
                }
                if (outputGuardPolicy.exhaustedAction()
                        == OutputGuardPolicy.ExhaustedAction.DEGRADE) {
                    record(ContextEvent.Type.OUTPUT_DEGRADED, context, "REPAIR_EXHAUSTED");
                    return new LlmCallResult(
                            new AgentDecision.Fallback("LLM_OUTPUT_INVALID"),
                            totalUsage,
                            AgentAssistantContent.EMPTY);
                }
                record(ContextEvent.Type.OUTPUT_EXHAUSTED, context, "REPAIR_EXHAUSTED");
                throw new AgentModelOutputException(
                        "LLM_OUTPUT_GUARD_EXHAUSTED", invalidOutput);
            }
        }
    }

    private ProviderResult send(
            AssembledContext context,
            List<Map<String, Object>> messages,
            Duration requestTimeout,
            CancellationToken cancellationToken) throws IOException, InterruptedException {
        cancellationToken.throwIfCancelled();
        Map<String, Object> body = requestBody(context, messages);
        HttpResponse<String> response = httpClient.send(
                request(context, body, requestTimeout), HttpResponse.BodyHandlers.ofString());
        if (isContextOverflow(response.statusCode(), response.body())) {
            throw new ContextOverflowException(response.statusCode());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("LLM provider returned HTTP " + response.statusCode());
        }
        cancellationToken.throwIfCancelled();
        Map<String, Object> responseBody = readMap(response.body());
        return new ProviderResult(extractAssistant(responseBody), usage(responseBody));
    }

    private ProviderResult sendStream(
            AssembledContext context,
            List<Map<String, Object>> messages,
            Duration requestTimeout,
            CancellationToken cancellationToken,
            Consumer<ChatChunk> chunks) throws IOException, InterruptedException {
        cancellationToken.throwIfCancelled();
        Map<String, Object> body = requestBody(context, messages);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(
                request(context, body, requestTimeout), HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody;
            try (var lines = response.body()) {
                errorBody = lines.collect(java.util.stream.Collectors.joining("\n"));
            }
            if (isContextOverflow(response.statusCode(), errorBody)) {
                throw new ContextOverflowException(response.statusCode());
            }
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new LlmStreamException(
                        "LLM_STREAM_RETRYABLE_HTTP_" + response.statusCode(), false, null);
            }
            throw new IllegalStateException("LLM provider returned HTTP " + response.statusCode());
        }

        ChatStreamAssembler assembler = new ChatStreamAssembler();
        long sequence = 0;
        boolean sawDone = false;
        Set<Integer> openToolCalls = new HashSet<>();
        Set<Integer> completedToolCalls = new HashSet<>();
        try (var lines = response.body()) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                cancellationToken.throwIfCancelled();
                String line = iterator.next();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    Map<Integer, ChatStreamAssembler.ToolCall> completeCalls = assembler.toolCalls()
                            .stream().collect(java.util.stream.Collectors.toMap(
                                    ChatStreamAssembler.ToolCall::index, call -> call));
                    for (Integer index : openToolCalls.stream()
                            .filter(candidate -> !completedToolCalls.contains(candidate))
                            .sorted().toList()) {
                        ChatChunk completedTool = new ChatChunk(
                                sequence++, "", "",
                                new ChatChunk.ToolCallDelta(index, "", "", "", true),
                                LlmUsage.UNMEASURED, null, false);
                        assembler.accept(completedTool);
                        chunks.accept(completedTool);
                        ChatStreamAssembler.ToolCall call = completeCalls.get(index);
                        if (call != null && !call.name().isBlank()
                                && !call.argumentsJson().isBlank()) {
                            context.decisionContext().dispatchEagerTool(
                                    index, call.name(), readMap(call.argumentsJson()));
                        }
                        completedToolCalls.add(index);
                    }
                    ChatChunk terminal = new ChatChunk(
                            sequence, "", "", null, LlmUsage.UNMEASURED, "done", true);
                    assembler.accept(terminal);
                    chunks.accept(terminal);
                    sawDone = true;
                    break;
                }
                Map<String, Object> event = readMap(data);
                LlmUsage eventUsage = usage(event);
                List<?> choices = list(event.get("choices"));
                if (choices.isEmpty()) {
                    if (eventUsage.measured()) {
                        ChatChunk usageChunk = new ChatChunk(
                                sequence++, "", "", null, eventUsage, null, false);
                        assembler.accept(usageChunk);
                        chunks.accept(usageChunk);
                    }
                    continue;
                }
                Map<String, Object> choice = map(choices.getFirst());
                Map<String, Object> delta = map(choice.get("delta"));
                String finishReason = text(choice.get("finish_reason"));
                String content = rawText(delta.get("content"));
                String reasoning = rawText(delta.get("reasoning_content"));
                if (!content.isEmpty() || !reasoning.isEmpty() || eventUsage.measured()
                        || !finishReason.isEmpty()) {
                    ChatChunk chunk = new ChatChunk(
                            sequence++, content, reasoning, null, eventUsage,
                            finishReason.isEmpty() ? null : finishReason, false);
                    assembler.accept(chunk);
                    chunks.accept(chunk);
                }
                for (Object value : list(delta.get("tool_calls"))) {
                    Map<String, Object> tool = map(value);
                    int index = intValue(tool.get("index"));
                    Map<String, Object> function = map(tool.get("function"));
                    openToolCalls.add(index);
                    ChatChunk toolChunk = new ChatChunk(
                            sequence++, "", "",
                            new ChatChunk.ToolCallDelta(
                                    index,
                                    rawText(tool.get("id")),
                                    rawText(function.get("name")),
                                    rawText(function.get("arguments")),
                                    false),
                            LlmUsage.UNMEASURED, null, false);
                    assembler.accept(toolChunk);
                    chunks.accept(toolChunk);
                }
                if ("tool_calls".equals(finishReason)) {
                    Map<Integer, ChatStreamAssembler.ToolCall> completeCalls = assembler.toolCalls()
                            .stream().collect(java.util.stream.Collectors.toMap(
                                    ChatStreamAssembler.ToolCall::index, call -> call));
                    for (Integer index : openToolCalls.stream()
                            .filter(candidate -> !completedToolCalls.contains(candidate))
                            .sorted().toList()) {
                        ChatChunk completedTool = new ChatChunk(
                                sequence++, "", "",
                                new ChatChunk.ToolCallDelta(index, "", "", "", true),
                                LlmUsage.UNMEASURED, "tool_calls", false);
                        assembler.accept(completedTool);
                        chunks.accept(completedTool);
                        ChatStreamAssembler.ToolCall call = completeCalls.get(index);
                        if (call != null && !call.name().isBlank()
                                && !call.argumentsJson().isBlank()) {
                            context.decisionContext().dispatchEagerTool(
                                    index, call.name(), readMap(call.argumentsJson()));
                        }
                        completedToolCalls.add(index);
                    }
                }
            }
        } catch (AgentCancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException streamFailure) {
            CancellationCause cause = cancellationToken.cause();
            if (cause != null) {
                throw new AgentCancellationException(cause);
            }
            throw new LlmStreamException(
                    assembler.outputStarted()
                            ? "LLM_STREAM_INTERRUPTED_AFTER_OUTPUT"
                            : "LLM_STREAM_FAILED_BEFORE_OUTPUT",
                    assembler.outputStarted(), streamFailure);
        }
        if (!sawDone) {
            throw new LlmStreamException(
                    assembler.outputStarted()
                            ? "LLM_STREAM_INTERRUPTED_AFTER_OUTPUT" : "LLM_STREAM_EMPTY",
                    assembler.outputStarted(), null);
        }
        ChatStreamAssembler.Assembly assembled = assembler.finish();
        if (!assembled.outputStarted()) {
            throw new LlmStreamException("LLM_STREAM_EMPTY", false, null);
        }
        String decisionContent = assembled.content().isBlank()
                ? nativeToolDecision(assembled.toolCalls(), assembled.reasoning())
                : assembled.content();
        return new ProviderResult(
                new AssistantPayload(decisionContent, assembled.reasoning()),
                assembled.usage());
    }

    private Map<String, Object> requestBody(
            AssembledContext context,
            List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", requestModel(context));
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", messages);
        if (!context.tools().isEmpty()) {
            body.put("tools", context.tools().stream().map(tool -> Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", tool.inputSchema()))).toList());
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private HttpRequest request(
            AssembledContext context,
            Map<String, Object> body,
            Duration requestTimeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Request-Id", context.decisionContext().request().requestId())
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)));
        if (!context.decisionContext().agentRunId().isBlank()) {
            request.header("X-Agent-Run-Id", context.decisionContext().agentRunId());
        }
        Object traceparent = context.decisionContext().request().attributes().get("traceparent");
        if (traceparent instanceof String trace && validTraceparent(trace)) {
            request.header("traceparent", trace.trim());
        }
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        return request.build();
    }

    private String requestModel(AssembledContext context) {
        Object override = context.decisionContext().request().attributes().get("modelOverride");
        if (!(override instanceof String requested) || requested.isBlank()) {
            return model;
        }
        String normalized = requested.trim();
        if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9._:/-]+")) {
            throw new IllegalArgumentException("invalid LLM model override");
        }
        return normalized;
    }

    private static boolean validTraceparent(String value) {
        return value != null && value.trim().matches(
                "[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    private String nativeToolDecision(
            List<ChatStreamAssembler.ToolCall> calls,
            String reasoning) {
        if (calls.isEmpty()) {
            return requireText(reasoning, "LLM response content");
        }
        List<Map<String, Object>> normalized = calls.stream().map(call -> Map.<String, Object>of(
                "tool", requireText(call.name(), "tool name"),
                "arguments", readMap(call.argumentsJson()))).toList();
        return writeJson(calls.size() == 1
                ? Map.of(
                        "action", "call_tool",
                        "tool", normalized.getFirst().get("tool"),
                        "arguments", normalized.getFirst().get("arguments"))
                : Map.of("action", "call_tools", "calls", normalized));
    }

    private List<Map<String, Object>> repairMessages(
            AssembledContext context,
            String invalidOutput,
            RuntimeException error) {
        List<Map<String, Object>> repaired = new java.util.ArrayList<>(
                context.messages().stream().map(OpenAiCompatibleLlmClient::message).toList());
        repaired.add(Map.of(
                "role", "user",
                "content", "[output_repair] 上一个输出不是合法的 Agent Decision。"
                        + "只返回一个 JSON 对象，action 只能是 call_tool、call_tools、complete、clarify、fallback。"
                        + "不要解释，不要使用 Markdown。error=" + stableOutputError(error)
                        + " invalid_output=" + clip(invalidOutput, 4_000)));
        return List.copyOf(repaired);
    }

    private void record(ContextEvent.Type type, AssembledContext context, String reason) {
        var request = context.decisionContext().request();
        contextEvents.record(new ContextEvent(
                type,
                request.sessionId(),
                request.requestId(),
                context.estimatedTokens(),
                context.budgetTokens(),
                reason,
                clock.instant()));
    }

    private static boolean isContextOverflow(int status, String body) {
        if (status == 413) {
            return true;
        }
        if (status != 400 || body == null) {
            return false;
        }
        String normalized = body.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("context_length_exceeded")
                || normalized.contains("context length")
                || normalized.contains("maximum context")
                || normalized.contains("context window")
                || normalized.contains("prompt is too long")
                || normalized.contains("input is too long")
                || normalized.contains("too many tokens")
                || normalized.contains("reduce the length");
    }

    private static String stableOutputError(RuntimeException error) {
        if (error.getCause() instanceof JsonProcessingException) {
            return "INVALID_JSON";
        }
        return error instanceof IllegalArgumentException
                ? "INVALID_FIELD" : "INVALID_DECISION";
    }

    private static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "…";
    }

    private LlmUsage usage(Map<String, Object> response) {
        Map<String, Object> usage = map(response.get("usage"));
        if (usage.isEmpty()) {
            return LlmUsage.UNMEASURED;
        }
        long input = longValue(usage.get("prompt_tokens"));
        long output = longValue(usage.get("completion_tokens"));
        long total = longValue(usage.get("total_tokens"));
        if (total == 0) {
            total = input + output;
        }
        long costMicros = Math.round(
                input * inputUsdPerMillionTokens + output * outputUsdPerMillionTokens);
        long cached = longValue(map(usage.get("prompt_tokens_details")).get("cached_tokens"));
        long reasoning = longValue(
                map(usage.get("completion_tokens_details")).get("reasoning_tokens"));
        return new LlmUsage(input, output, total, costMicros, true, cached, reasoning);
    }

    private AgentDecision parseDecision(String content, AssembledContext context) {
        Map<String, Object> value = readMap(stripFence(content));
        String action = String.valueOf(value.get("action")).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (action) {
            case "call_tool" -> new AgentDecision.CallTool(
                    requireText(String.valueOf(value.get("tool")), "tool"),
                    map(value.get("arguments")));
            case "call_tools" -> new AgentDecision.CallTools(list(value.get("calls")).stream()
                    .map(this::toolCall)
                    .toList());
            case "complete" -> complete(map(value.get("output")), context);
            case "clarify" -> new AgentDecision.Clarify(
                    requireText(String.valueOf(value.get("question")), "clarification question"));
            case "fallback" -> new AgentDecision.Fallback(
                    requireText(String.valueOf(value.get("reason")), "fallback reason"));
            default -> throw new IllegalStateException("LLM provider returned an unsupported action: " + action);
        };
    }

    private AgentDecision complete(Map<String, Object> output, AssembledContext context) {
        List<AgentToolObservation> observations = context.decisionContext().observations();
        if (observations.isEmpty()) {
            return new AgentDecision.Complete(output);
        }
        String selectedTool = requireText(String.valueOf(output.get("selectedTool")), "selected tool");
        AgentToolObservation selected = observations.stream()
                .filter(observation -> observation.result().success())
                .filter(observation -> selectedTool.equals(observation.toolName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "LLM selected an unavailable or failed tool result: " + selectedTool));
        long successful = observations.stream().filter(observation -> observation.result().success()).count();
        Map<String, Object> resolved = new LinkedHashMap<>(selected.result().output());
        resolved.put("selectedTool", selected.toolName());
        resolved.put("successfulToolCount", successful);
        resolved.put("candidateSetReused", true);
        return new AgentDecision.Complete(resolved);
    }

    private AgentDecision.ToolCall toolCall(Object value) {
        Map<String, Object> call = map(value);
        return new AgentDecision.ToolCall(
                requireText(String.valueOf(call.get("tool")), "tool"),
                map(call.get("arguments")));
    }

    private static Map<String, Object> message(ContextMessage message) {
        if ("tool".equals(message.role())) {
            return Map.of("role", "user", "content", "[tool_observation] " + message.content());
        }
        return Map.of("role", message.role(), "content", message.content());
    }

    private AssistantPayload extractAssistant(Map<String, Object> response) {
        List<?> choices = list(response.get("choices"));
        if (choices.isEmpty()) {
            throw new IllegalStateException("LLM provider response has no choices");
        }
        Map<String, Object> first = map(choices.getFirst());
        Map<String, Object> message = map(first.get("message"));
        String content = text(message.get("content"));
        if (!content.isBlank()) {
            return new AssistantPayload(content, text(message.get("reasoning_content")));
        }
        String reasoningContent = text(message.get("reasoning_content"));
        return new AssistantPayload(
                requireText(reasoningContent, "LLM response content"),
                reasoningContent);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize LLM request", error);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to parse LLM JSON", error);
        }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (item != null) {
                normalized.put(String.valueOf(key), item);
            }
        });
        return Map.copyOf(normalized);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private static String rawText(Object value) {
        return value instanceof String text ? text : "";
    }

    private static String text(Object value) {
        return value instanceof String text ? text.trim() : "";
    }

    private static String stripFence(String value) {
        String stripped = value.trim();
        if (stripped.startsWith("```")) {
            stripped = stripped.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
        }
        return stripped;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private record AssistantPayload(String decisionContent, String reasoningContent) {
    }

    private record ProviderResult(AssistantPayload assistant, LlmUsage usage) {
    }

    private static final class PreOutputBuffer implements Consumer<ChatChunk> {
        private final Consumer<ChatChunk> downstream;
        private final List<ChatChunk> pending = new ArrayList<>();
        private boolean outputStarted;

        private PreOutputBuffer(Consumer<ChatChunk> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void accept(ChatChunk chunk) {
            if (outputStarted) {
                downstream.accept(chunk);
                return;
            }
            if (!chunk.hasOutput()) {
                pending.add(chunk);
                return;
            }
            outputStarted = true;
            pending.forEach(downstream);
            pending.clear();
            downstream.accept(chunk);
        }
    }
}
