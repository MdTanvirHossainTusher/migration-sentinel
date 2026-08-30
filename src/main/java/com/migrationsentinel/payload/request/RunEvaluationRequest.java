package com.migrationsentinel.payload.request;

import com.migrationsentinel.model.enums.ReviewMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Run the migration-safety evaluation corpus through one pipeline mode")
public record RunEvaluationRequest(

        @Schema(description = "Pipeline mode to evaluate", defaultValue = "ANALYZER_VERIFIER_SPLIT")
        ReviewMode mode,

        @Schema(description = "LLM provider", defaultValue = "heuristic")
        String provider,

        @Schema(description = "Optional subset of case ids; empty means the whole corpus")
        List<String> caseIds,

        @Schema(description = "Free-text label stored on the run", example = "baseline-vs-agent-2026-08-29")
        String corpusLabel,

        @Schema(description = "Optional: your own API key for this run, used instead of any server-configured "
                + "key. Encrypted at rest, never returned, stripped from logs.")
        String llmApiKey
) {
    public ReviewMode modeOrDefault() {
        return mode == null ? ReviewMode.ANALYZER_VERIFIER_SPLIT : mode;
    }
}
