package com.migrationsentinel.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.migrationsentinel.model.enums.FindingVerdict;
import com.migrationsentinel.model.enums.RuleCode;
import com.migrationsentinel.model.enums.Severity;
import com.migrationsentinel.payload.dto.ProposedFinding;
import com.migrationsentinel.payload.dto.VerifiedFinding;
import com.migrationsentinel.service.support.AgentJsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tolerant parsing of the JSON the agents return (handles code fences and surrounding prose). */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentJson {

    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);
    private static final Pattern BARE_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final AgentJsonMapper mapper;

    public JsonNode extractObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return mapper.createObjectNode();
        }
        Matcher fence = FENCE.matcher(raw);
        if (fence.find()) {
            return parse(fence.group(1));
        }
        Matcher bare = BARE_OBJECT.matcher(raw.trim());
        if (bare.find()) {
            return parse(bare.group());
        }
        return mapper.createObjectNode();
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.warn("agent returned unparseable JSON: {}", e.getMessage());
            return mapper.createObjectNode();
        }
    }

    public List<ProposedFinding> parseFindings(String raw) {
        JsonNode root = extractObject(raw);
        List<ProposedFinding> out = new ArrayList<>();
        for (JsonNode f : root.path("findings")) {
            RuleCode rule = enumOrNull(RuleCode.class, f.path("ruleCode").asText());
            if (rule == null) {
                log.debug("dropping finding with unknown rule code: {}", f.path("ruleCode").asText());
                continue;
            }
            out.add(new ProposedFinding(
                    rule,
                    enumOr(Severity.class, f.path("severity").asText(), Severity.MEDIUM),
                    text(f, "title", rule.name()),
                    f.path("targetObject").asText(null),
                    text(f, "summary", ""),
                    f.path("evidence").asText(""),
                    f.hasNonNull("suggestedRewrite") ? f.path("suggestedRewrite").asText() : null,
                    f.has("confidence") ? f.path("confidence").asDouble() : 0.7));
        }
        return out;
    }

    public List<VerifiedFinding> applyVerdicts(List<ProposedFinding> proposed, String verifierRaw) {
        JsonNode root = extractObject(verifierRaw);
        JsonNode verdicts = root.path("verdicts");
        List<VerifiedFinding> out = new ArrayList<>();
        for (int i = 0; i < proposed.size(); i++) {
            ProposedFinding pf = proposed.get(i);
            JsonNode v = matchVerdict(verdicts, pf, i);
            FindingVerdict verdict = enumOr(FindingVerdict.class, v.path("verdict").asText(), FindingVerdict.UNVERIFIED);
            String note = v.path("note").asText("");
            String severityOverride = v.path("severityOverride").asText(null);
            ProposedFinding adjusted = pf;
            Severity sev = enumOrNull(Severity.class, severityOverride);
            if (sev != null) {
                adjusted = new ProposedFinding(pf.ruleCode(), sev, pf.title(), pf.targetObject(),
                        pf.summary(), pf.evidence(), pf.suggestedRewrite(), pf.confidence());
            }
            if (verdict == FindingVerdict.REJECTED) {
                continue;
            }
            out.add(new VerifiedFinding(adjusted, verdict, note));
        }
        return out;
    }

    private JsonNode matchVerdict(JsonNode verdicts, ProposedFinding pf, int index) {
        for (JsonNode v : verdicts) {
            if (v.path("ruleCode").asText().equals(pf.ruleCode().name())
                    && v.path("targetObject").asText("").equalsIgnoreCase(
                            pf.targetObject() == null ? "" : pf.targetObject())) {
                return v;
            }
        }
        if (verdicts.isArray() && index < verdicts.size()) {
            return verdicts.get(index);
        }
        return mapper.createObjectNode();
    }

    private <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private <E extends Enum<E>> E enumOr(Class<E> type, String value, E fallback) {
        E parsed = enumOrNull(type, value);
        return parsed == null ? fallback : parsed;
    }

    private String text(JsonNode node, String field, String fallback) {
        String v = node.path(field).asText("");
        return v.isBlank() ? fallback : v;
    }
}
