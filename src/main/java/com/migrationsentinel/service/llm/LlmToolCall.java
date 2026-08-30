package com.migrationsentinel.service.llm;

/**
 * One tool/function call the model asked for.
 *
 * @param thoughtSignature Gemini 3+ returns an opaque signature on each function-call part
 *                         and rejects the follow-up turn if it is not echoed back. Null for
 *                         providers that do not use it (OpenAI, the heuristic client).
 */
public record LlmToolCall(String id, String name, String argumentsJson, String thoughtSignature) {

    public LlmToolCall(String id, String name, String argumentsJson) {
        this(id, name, argumentsJson, null);
    }
}
