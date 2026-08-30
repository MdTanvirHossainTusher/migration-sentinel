package com.migrationsentinel.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.migrationsentinel.config.properties.LlmProperties;
import com.migrationsentinel.exception.LlmProviderException;
import com.migrationsentinel.service.agent.ToolSpec;
import com.migrationsentinel.service.support.AgentJsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** OpenAI Chat Completions client with function calling. Raw HTTP — no SDK dependency. */
@Slf4j
@Component
public class OpenAiLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final AgentJsonMapper mapper;
    private final String apiKeyOverride;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    public OpenAiLlmClient(LlmProperties properties, AgentJsonMapper mapper) {
        this(properties, mapper, null);
    }

    private OpenAiLlmClient(LlmProperties properties, AgentJsonMapper mapper, String apiKeyOverride) {
        this.properties = properties;
        this.mapper = mapper;
        this.apiKeyOverride = apiKeyOverride;
    }

    @Override
    public LlmClient withApiKey(String apiKey) {
        return new OpenAiLlmClient(properties, mapper, apiKey);
    }

    private String apiKey() {
        return apiKeyOverride != null && !apiKeyOverride.isBlank()
                ? apiKeyOverride : properties.getOpenai().getApiKey();
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public boolean available() {
        return apiKey() != null && !apiKey().isBlank();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<ToolSpec> tools) {
        if (!available()) {
            throw new LlmProviderException("OpenAI API key is not configured (sentinel.llm.openai.api-key "
                    + "or a per-request llm_api_key)");
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", properties.getOpenai().getModel());
            body.put("temperature", 0);
            body.set("messages", encodeMessages(messages));
            if (tools != null && !tools.isEmpty()) {
                body.set("tools", encodeTools(tools));
                body.put("tool_choice", "auto");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getOpenai().getBaseUrl() + "/chat/completions"))
                    .timeout(properties.getRequestTimeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = LlmHttp.sendWithBackoff(http, request, "OpenAI");
            if (response.statusCode() / 100 != 2) {
                throw new LlmProviderException(providerError("OpenAI", response.statusCode(), response.body()));
            }
            JsonNode message = mapper.readTree(response.body()).path("choices").path(0).path("message");
            List<LlmToolCall> calls = new ArrayList<>();
            for (JsonNode tc : message.path("tool_calls")) {
                calls.add(new LlmToolCall(
                        tc.path("id").asText(),
                        tc.path("function").path("name").asText(),
                        tc.path("function").path("arguments").asText("{}")));
            }
            if (!calls.isEmpty()) {
                return LlmResponse.callTools(calls);
            }
            return LlmResponse.finalAnswer(message.path("content").asText(""));
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmProviderException("OpenAI request failed: " + e.getMessage());
        }
    }

    private ArrayNode encodeMessages(List<LlmMessage> messages) {
        ArrayNode arr = mapper.createArrayNode();
        for (LlmMessage m : messages) {
            ObjectNode node = mapper.createObjectNode();
            switch (m.role()) {
                case SYSTEM -> {
                    node.put("role", "system");
                    node.put("content", m.content());
                }
                case USER -> {
                    node.put("role", "user");
                    node.put("content", m.content());
                }
                case ASSISTANT -> {
                    node.put("role", "assistant");
                    node.put("content", m.content() == null ? "" : m.content());
                    if (!m.toolCalls().isEmpty()) {
                        ArrayNode tcs = mapper.createArrayNode();
                        for (LlmToolCall tc : m.toolCalls()) {
                            ObjectNode t = mapper.createObjectNode();
                            t.put("id", tc.id());
                            t.put("type", "function");
                            ObjectNode fn = mapper.createObjectNode();
                            fn.put("name", tc.name());
                            fn.put("arguments", tc.argumentsJson());
                            t.set("function", fn);
                            tcs.add(t);
                        }
                        node.set("tool_calls", tcs);
                    }
                }
                case TOOL -> {
                    node.put("role", "tool");
                    node.put("tool_call_id", m.toolCallId());
                    node.put("content", m.content());
                }
            }
            arr.add(node);
        }
        return arr;
    }

    private ArrayNode encodeTools(List<ToolSpec> tools) {
        ArrayNode arr = mapper.createArrayNode();
        for (ToolSpec spec : tools) {
            ObjectNode t = mapper.createObjectNode();
            t.put("type", "function");
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", spec.name());
            fn.put("description", spec.description());
            fn.set("parameters", mapper.valueToTree(spec.parameters()));
            t.set("function", fn);
            arr.add(t);
        }
        return arr;
    }

    /** Pull the readable sentence out of {@code {"error":{"message": ...}}} for the UI. */
    private String providerError(String provider, int status, String body) {
        try {
            JsonNode error = mapper.readTree(body).path("error");
            String message = error.path("message").asText("");
            if (!message.isBlank()) {
                String code = error.path("code").isMissingNode()
                        ? String.valueOf(status) : error.path("code").asText(String.valueOf(status));
                return provider + " API error (" + code + "): " + message;
            }
        } catch (Exception ignored) {
            // fall through to the raw body
        }
        return provider + " returned HTTP " + status + ": " + truncate(body);
    }

    private String truncate(String s) {
        return s == null ? "" : s.length() > 400 ? s.substring(0, 400) : s;
    }
}
