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
import java.util.UUID;

/** Google Gemini generateContent client with function calling. Raw HTTP — no SDK dependency. */
@Slf4j
@Component
public class GeminiLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final AgentJsonMapper mapper;
    private final String apiKeyOverride;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    public GeminiLlmClient(LlmProperties properties, AgentJsonMapper mapper) {
        this(properties, mapper, null);
    }

    private GeminiLlmClient(LlmProperties properties, AgentJsonMapper mapper, String apiKeyOverride) {
        this.properties = properties;
        this.mapper = mapper;
        this.apiKeyOverride = apiKeyOverride;
    }

    @Override
    public LlmClient withApiKey(String apiKey) {
        return new GeminiLlmClient(properties, mapper, apiKey);
    }

    private String apiKey() {
        return apiKeyOverride != null && !apiKeyOverride.isBlank()
                ? apiKeyOverride : properties.getGemini().getApiKey();
    }

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public boolean available() {
        return apiKey() != null && !apiKey().isBlank();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<ToolSpec> tools) {
        if (!available()) {
            throw new LlmProviderException("Gemini API key is not configured (sentinel.llm.gemini.api-key "
                    + "or a per-request llm_api_key)");
        }
        try {
            ObjectNode body = mapper.createObjectNode();

            String system = messages.stream().filter(m -> m.role() == LlmMessage.Role.SYSTEM)
                    .map(LlmMessage::content).findFirst().orElse(null);
            if (system != null) {
                ObjectNode sys = mapper.createObjectNode();
                ArrayNode parts = mapper.createArrayNode();
                parts.add(mapper.createObjectNode().put("text", system));
                sys.set("parts", parts);
                body.set("systemInstruction", sys);
            }
            body.set("contents", encodeContents(messages));
            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsArr = mapper.createArrayNode();
                ObjectNode toolNode = mapper.createObjectNode();
                ArrayNode decls = mapper.createArrayNode();
                for (ToolSpec spec : tools) {
                    ObjectNode d = mapper.createObjectNode();
                    d.put("name", spec.name());
                    d.put("description", spec.description());
                    d.set("parameters", mapper.valueToTree(spec.parameters()));
                    decls.add(d);
                }
                toolNode.set("functionDeclarations", decls);
                toolsArr.add(toolNode);
                body.set("tools", toolsArr);
            }
            ObjectNode genConfig = mapper.createObjectNode();
            genConfig.put("temperature", 0);
            body.set("generationConfig", genConfig);

            String url = properties.getGemini().getBaseUrl() + "/models/" + properties.getGemini().getModel()
                    + ":generateContent?key=" + apiKey();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(properties.getRequestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = LlmHttp.sendWithBackoff(http, request, "Gemini");
            if (response.statusCode() / 100 != 2) {
                throw new LlmProviderException(providerError("Gemini", response.statusCode(), response.body()));
            }
            JsonNode parts = mapper.readTree(response.body())
                    .path("candidates").path(0).path("content").path("parts");
            List<LlmToolCall> calls = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.has("functionCall")) {
                    JsonNode fc = part.get("functionCall");
                    // Gemini 3+ attaches an opaque thoughtSignature to each function-call part
                    // and rejects the next turn if it is not echoed back verbatim.
                    String signature = part.path("thoughtSignature").asText(null);
                    calls.add(new LlmToolCall("gemini_" + UUID.randomUUID(),
                            fc.path("name").asText(),
                            fc.path("args").toString(),
                            signature));
                } else if (part.has("text")) {
                    text.append(part.get("text").asText());
                }
            }
            if (!calls.isEmpty()) {
                return LlmResponse.callTools(calls);
            }
            return LlmResponse.finalAnswer(text.toString());
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmProviderException("Gemini request failed: " + e.getMessage());
        }
    }

    private ArrayNode encodeContents(List<LlmMessage> messages) {
        ArrayNode arr = mapper.createArrayNode();
        for (LlmMessage m : messages) {
            if (m.role() == LlmMessage.Role.SYSTEM) {
                continue;
            }
            ObjectNode content = mapper.createObjectNode();
            ArrayNode parts = mapper.createArrayNode();
            switch (m.role()) {
                case USER -> {
                    content.put("role", "user");
                    parts.add(mapper.createObjectNode().put("text", m.content()));
                }
                case ASSISTANT -> {
                    content.put("role", "model");
                    if (m.content() != null && !m.content().isBlank()) {
                        parts.add(mapper.createObjectNode().put("text", m.content()));
                    }
                    for (LlmToolCall tc : m.toolCalls()) {
                        ObjectNode fc = mapper.createObjectNode();
                        ObjectNode call = mapper.createObjectNode();
                        call.put("name", tc.name());
                        try {
                            call.set("args", mapper.readTree(tc.argumentsJson().isBlank() ? "{}" : tc.argumentsJson()));
                        } catch (Exception e) {
                            call.set("args", mapper.createObjectNode());
                        }
                        fc.set("functionCall", call);
                        if (tc.thoughtSignature() != null && !tc.thoughtSignature().isBlank()) {
                            fc.put("thoughtSignature", tc.thoughtSignature());
                        }
                        parts.add(fc);
                    }
                }
                case TOOL -> {
                    content.put("role", "user");
                    ObjectNode fr = mapper.createObjectNode();
                    ObjectNode resp = mapper.createObjectNode();
                    resp.put("name", m.toolName());
                    ObjectNode inner = mapper.createObjectNode();
                    inner.put("result", m.content());
                    resp.set("response", inner);
                    fr.set("functionResponse", resp);
                    parts.add(fr);
                }
                default -> {
                }
            }
            content.set("parts", parts);
            arr.add(content);
        }
        return arr;
    }

    /**
     * Turn a provider error body into one readable sentence. Both OpenAI and Gemini wrap the
     * useful text in {@code {"error":{"message": "...", "status": "..."}}}; pull that out so
     * the UI shows "This model is no longer available…" instead of a page of JSON.
     */
    private String providerError(String provider, int status, String body) {
        try {
            JsonNode error = mapper.readTree(body).path("error");
            String message = error.path("message").asText("");
            if (!message.isBlank()) {
                String code = error.path("status").asText(String.valueOf(status));
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
