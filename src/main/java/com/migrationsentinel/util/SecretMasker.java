package com.migrationsentinel.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Redacts anything that looks like a credential before it reaches a console line, an audit
 * payload, a Kafka record or a persisted agent trajectory. Deliberately a plain static
 * utility with compiled-in defaults: the logback appender that calls it runs before the
 * Spring context exists. {@link #configure(List)} lets {@code RedactionConfig} widen the
 * pattern set from {@code redaction.xml} once the context is up.
 */
public final class SecretMasker {

    /** What a matched secret is replaced with (a trailing group is preserved when present). */
    public static final String MASK = "***REDACTED***";

    private static final List<Pattern> DEFAULTS = List.of(
            // OpenAI-style keys: sk-... / sk-proj-...
            Pattern.compile("sk-[A-Za-z0-9_-]{16,}"),
            // Google API keys: AIza...
            Pattern.compile("AIza[0-9A-Za-z_-]{20,}"),
            // Bearer tokens
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._-]{12,}"),
            // key/secret assignments in text or JSON: api_key=..., "apiKey": "...", secret: ...
            Pattern.compile("(?i)((?:api[_-]?key|secret[_-]?key|access[_-]?key|password|token)\"?\\s*[:=]\\s*\"?)"
                    + "([^\"\\s,;}]{4,})"),
            // JDBC url passwords
            Pattern.compile("(?i)(password=)([^&\\s;]+)"));

    private static volatile List<Pattern> patterns = DEFAULTS;

    private SecretMasker() {
    }

    /** Replace the active pattern set (defaults are always kept as a floor). */
    public static void configure(List<Pattern> extra) {
        if (extra == null || extra.isEmpty()) {
            patterns = DEFAULTS;
            return;
        }
        java.util.ArrayList<Pattern> merged = new java.util.ArrayList<>(DEFAULTS);
        merged.addAll(extra);
        patterns = List.copyOf(merged);
    }

    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = input;
        for (Pattern p : patterns) {
            java.util.regex.Matcher m = p.matcher(out);
            if (!m.find()) {
                continue;
            }
            m.reset();
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String replacement = m.groupCount() >= 2
                        ? java.util.regex.Matcher.quoteReplacement(m.group(1) + MASK)
                        : MASK;
                m.appendReplacement(sb, replacement);
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }
}
