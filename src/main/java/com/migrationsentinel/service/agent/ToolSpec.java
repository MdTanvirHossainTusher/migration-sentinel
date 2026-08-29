package com.migrationsentinel.service.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * One tool the agent loop can call. {@code parameters} is a JSON-Schema object so the
 * same spec drives both the OpenAI/Gemini function-calling payload and the offline
 * heuristic client.
 */
public record ToolSpec(
        String name,
        String description,
        Map<String, Object> parameters,
        Handler handler
) {
    @FunctionalInterface
    public interface Handler {
        String execute(JsonNode args) throws Exception;
    }

    public static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of(required)
        );
    }

    public static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }
}
