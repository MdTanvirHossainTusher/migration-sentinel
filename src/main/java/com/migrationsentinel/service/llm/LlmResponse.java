package com.migrationsentinel.service.llm;

import java.util.List;

/**
 * One assistant turn. If {@code toolCalls} is non-empty the loop executes them and
 * continues; otherwise {@code content} is the final answer.
 */
public record LlmResponse(String content, List<LlmToolCall> toolCalls) {

    public boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static LlmResponse finalAnswer(String content) {
        return new LlmResponse(content, List.of());
    }

    public static LlmResponse callTools(List<LlmToolCall> calls) {
        return new LlmResponse(null, calls);
    }
}
