package com.migrationsentinel.service.llm;

import com.migrationsentinel.config.properties.LlmProperties;
import com.migrationsentinel.exception.LlmProviderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClientRegistry {

    private final List<LlmClient> clients;
    private final HeuristicLlmClient heuristic;
    private final LlmProperties properties;

    /** Resolve by explicit name, falling back to the configured default, then to heuristic. */
    public LlmClient resolve(String requested) {
        return resolve(requested, null);
    }

    /**
     * Resolve a client, optionally binding a per-request API key. With a key present the
     * client is usable even when nothing is configured on the server — that is the whole
     * point of the field.
     */
    public LlmClient resolve(String requested, String apiKey) {
        String name = requested != null && !requested.isBlank() ? requested : properties.getProvider();
        LlmClient base = clients.stream()
                .filter(c -> c.provider().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new LlmProviderException("Unknown LLM provider: " + name));

        LlmClient client = apiKey != null && !apiKey.isBlank() ? base.withApiKey(apiKey) : base;
        if (!client.available()) {
            throw new LlmProviderException("LLM provider '" + name + "' is selected but not configured "
                    + "(no API key on the server or the request). Use provider=heuristic to run offline.");
        }
        return client;
    }

    public LlmClient heuristic() {
        return heuristic;
    }
}
