package com.migrationsentinel.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sentinel.llm")
public class LlmProperties {

    /**
     * Which client backs the agent: {@code heuristic} (offline, deterministic — the default so the
     * whole system runs with no API key), {@code openai}, or {@code gemini}.
     */
    private String provider = "heuristic";

    private int maxToolIterations = 12;
    private Duration requestTimeout = Duration.ofSeconds(90);

    private final Openai openai = new Openai();
    private final Gemini gemini = new Gemini();

    @Getter
    @Setter
    public static class Openai {
        private String apiKey = "";
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-5.6-luna";

        /**
         * {@code reasoning_effort} for the GPT-5 line. Chat Completions + function tools on a
         * gpt-5.* model requires this to be {@code none}; it is only sent for models that
         * accept it, so switching {@code model} back to gpt-4o-mini needs no change here.
         */
        private String reasoningEffort = "none";
    }

    @Getter
    @Setter
    public static class Gemini {
        private String apiKey = "";
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        private String model = "gemini-flash-latest";
    }
}
