package io.seekflux.apps.agentserver.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.AgentToolObservation;
import io.seekflux.platform.agentruntime.context.AssembledContext;
import io.seekflux.platform.agentruntime.context.ContextMessage;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenAiCompatibleLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final String version;

    public OpenAiCompatibleLlmClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI endpoint,
            String apiKey,
            String model,
            Duration timeout) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = requireText(model, "LLM model");
        this.timeout = timeout;
        this.version = "openai-compatible:" + this.model + ":v1";
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public AgentDecision chat(AssembledContext context) {
        Duration requestTimeout = context.decisionContext().remaining().compareTo(timeout) < 0
                ? context.decisionContext().remaining()
                : timeout;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", context.messages().stream().map(OpenAiCompatibleLlmClient::message).toList());
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)));
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("LLM provider returned HTTP " + response.statusCode());
            }
            return parseDecision(extractContent(readMap(response.body())), context);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM provider call was interrupted", interrupted);
        } catch (IOException error) {
            throw new IllegalStateException("LLM provider call failed", error);
        }
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

    private String extractContent(Map<String, Object> response) {
        List<?> choices = list(response.get("choices"));
        if (choices.isEmpty()) {
            throw new IllegalStateException("LLM provider response has no choices");
        }
        Map<String, Object> first = map(choices.getFirst());
        Map<String, Object> message = map(first.get("message"));
        return requireText(String.valueOf(message.get("content")), "LLM response content");
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
}
