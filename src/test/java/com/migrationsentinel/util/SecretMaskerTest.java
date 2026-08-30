package com.migrationsentinel.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feeds the masker credential-shaped strings and checks they come back redacted. The sample
 * values are assembled from fragments at runtime on purpose — nothing that looks like a real
 * key is ever written into this file, so a secret scanner has nothing to flag.
 */
class SecretMaskerTest {

    private static final String FAKE = "0123456789abcdef0123456789";

    @Test
    void masksOpenAiStyleKeys() {
        String key = "sk-" + "proj-" + FAKE;
        String masked = SecretMasker.mask("using key " + key + " for the call");
        assertThat(masked).doesNotContain(key);
        assertThat(masked).contains(SecretMasker.MASK);
    }

    @Test
    void masksGoogleKeysAndBearerTokens() {
        String googleKey = "AIza" + FAKE + FAKE;
        assertThat(SecretMasker.mask("key=" + googleKey)).doesNotContain(googleKey);

        String token = "ey" + FAKE + "." + FAKE + "." + FAKE;
        assertThat(SecretMasker.mask("Authorization: Bearer " + token)).doesNotContain(token);
    }

    @Test
    void masksKeyValueAssignmentsButKeepsTheLabel() {
        String value = "value-" + FAKE;
        String masked = SecretMasker.mask("{\"llm_api_key\":\"" + value + "\",\"mode\":\"FULL\"}");
        assertThat(masked).contains("llm_api_key");
        assertThat(masked).doesNotContain(value);
        assertThat(masked).contains("FULL");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        String text = "review 42 completed with 3 findings, sandbox used";
        assertThat(SecretMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void nullSafe() {
        assertThat(SecretMasker.mask(null)).isNull();
    }
}
