package com.migrationsentinel.service;

import com.migrationsentinel.config.properties.LlmProperties;
import com.migrationsentinel.payload.response.HealthResponse;
import com.migrationsentinel.service.eval.EvaluationCorpus;
import com.migrationsentinel.service.llm.LlmClient;
import com.migrationsentinel.service.sandbox.SandboxManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HealthService {

    private final SandboxManager sandboxManager;
    private final EvaluationCorpus corpus;
    private final LlmProperties llmProperties;
    private final List<LlmClient> llmClients;

    @Value("${app.version:dev}")
    private String version;

    public HealthResponse health() {
        return new HealthResponse(
                "UP",
                version,
                llmProperties.getProvider(),
                llmClients.stream().filter(LlmClient::available).map(LlmClient::provider).toList(),
                sandboxManager.dockerAvailable(),
                safeCorpusSize(),
                Instant.now());
    }

    private int safeCorpusSize() {
        try {
            return corpus.size();
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
