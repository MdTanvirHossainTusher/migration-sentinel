package com.migrationsentinel.service.llm;

import com.migrationsentinel.service.agent.ToolSpec;

import java.util.List;

public interface LlmClient {

    /** {@code heuristic}, {@code openai} or {@code gemini}. */
    String provider();

    /** True when the client can actually be used (API key present, etc.). */
    boolean available();

    LlmResponse chat(List<LlmMessage> messages, List<ToolSpec> tools);

    /**
     * Return a view of this client that uses {@code apiKey} instead of any server-configured
     * one, for a single review or evaluation. The default (heuristic) ignores it.
     */
    default LlmClient withApiKey(String apiKey) {
        return this;
    }
}
