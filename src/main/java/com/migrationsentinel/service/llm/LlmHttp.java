package com.migrationsentinel.service.llm;

import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One place for the "the provider told us to slow down" logic that OpenAI and Gemini share.
 * A rate-limited free-tier key returns 429 with a "retry in N seconds" hint; without this a
 * 15-case evaluation fails every case after the first. Retries 429/503 a few times, honouring
 * the hint (or {@code Retry-After}, or an exponential fallback), then hands the last response
 * back for the caller to turn into a readable error.
 */
@Slf4j
final class LlmHttp {

    private static final int MAX_RETRIES = 4;
    private static final long MAX_WAIT_MS = 65_000;
    private static final Pattern RETRY_HINT =
            Pattern.compile("retry in ([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);

    private LlmHttp() {
    }

    static HttpResponse<String> sendWithBackoff(HttpClient http, HttpRequest request, String provider)
            throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 429 && status != 503) {
                return response;
            }
            if (attempt == MAX_RETRIES) {
                return response;
            }
            long waitMs = retryDelayMs(response, attempt);
            log.warn("{} returned {} (attempt {}/{}); backing off {} ms",
                    provider, status, attempt + 1, MAX_RETRIES, waitMs);
            Thread.sleep(waitMs);
        }
        return response;
    }

    private static long retryDelayMs(HttpResponse<String> response, int attempt) {
        Matcher hint = RETRY_HINT.matcher(response.body() == null ? "" : response.body());
        if (hint.find()) {
            return clamp((long) (Double.parseDouble(hint.group(1)) * 1000) + 500);
        }
        return response.headers().firstValue("retry-after")
                .map(String::trim)
                .filter(s -> s.matches("\\d+"))
                .map(s -> clamp(Long.parseLong(s) * 1000))
                .orElse(clamp((long) Math.pow(2, attempt + 1) * 1000));
    }

    private static long clamp(long ms) {
        return Math.min(Math.max(ms, 1_000), MAX_WAIT_MS);
    }
}
