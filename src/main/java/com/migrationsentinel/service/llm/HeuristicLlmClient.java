package com.migrationsentinel.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.migrationsentinel.config.properties.SentinelProperties;
import com.migrationsentinel.model.enums.RuleCode;
import com.migrationsentinel.payload.dto.ProposedFinding;
import com.migrationsentinel.payload.dto.SchemaDriftReport;
import com.migrationsentinel.service.agent.ToolSpec;
import com.migrationsentinel.service.rules.SchemaFacts;
import com.migrationsentinel.service.rules.StaticRuleScanner;
import com.migrationsentinel.service.support.AgentJsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The default, offline agent brain. It is a fixed policy rather than a model: with tools,
 * it calls the sandbox and the deterministic scanner, then turns their output into the
 * same JSON the LLM clients emit; with no tools (baseline mode) it runs a structure-only
 * scan with no schema knowledge — deliberately weaker, so the improvement curve is real.
 * It exists so the whole pipeline runs end to end with no API key, which is what makes
 * the evaluation reproducible from a clean environment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeuristicLlmClient implements LlmClient {

    private static final Pattern SQL_BLOCK = Pattern.compile(
            "MIGRATION UNDER REVIEW.*?```sql\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern ANALYZER_FINDINGS_BLOCK = Pattern.compile(
            "ANALYZER FINDINGS TO VERIFY:\\s*```json\\s*(.*?)```", Pattern.DOTALL);

    private final AgentJsonMapper mapper;
    private final StaticRuleScanner staticScanner;
    private final SentinelProperties properties;

    @Override
    public String provider() {
        return "heuristic";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<ToolSpec> tools) {
        String systemPrompt = messages.stream()
                .filter(m -> m.role() == LlmMessage.Role.SYSTEM)
                .map(LlmMessage::content).findFirst().orElse("");
        boolean verifier = systemPrompt.toLowerCase().contains("verifier");

        if (tools.isEmpty() && !verifier) {
            return LlmResponse.finalAnswer(buildStructuralFindings(userText(messages)));
        }

        Set<String> calledTools = messages.stream()
                .filter(m -> m.role() == LlmMessage.Role.TOOL)
                .map(LlmMessage::toolName).collect(Collectors.toSet());
        Set<String> availableTools = tools.stream().map(ToolSpec::name).collect(Collectors.toSet());

        for (String next : List.of("run_candidate_migration", "static_scan", "validate_entities")) {
            if (availableTools.contains(next) && !calledTools.contains(next)) {
                return LlmResponse.callTools(List.of(
                        new LlmToolCall("call_" + next + "_" + UUID.randomUUID(), next, "{}")));
            }
        }

        return verifier
                ? LlmResponse.finalAnswer(buildVerdicts(messages))
                : LlmResponse.finalAnswer(buildFindings(messages));
    }

    // ── baseline (no tools, no schema) ──────────────────────────────────────

    private String buildStructuralFindings(String userText) {
        String sql = extractSql(userText);
        List<ProposedFinding> findings = staticScanner.scan(
                sql,
                SchemaFacts.empty(properties.getLargeTableRowThreshold()),
                SchemaDriftReport.notRun("no sandbox in baseline mode"));
        ArrayNode arr = mapper.valueToTree(findings);
        ObjectNode out = mapper.createObjectNode();
        out.set("findings", arr);
        out.put("summary", findings.isEmpty()
                ? "No obvious defects from the SQL text alone."
                : findings.size() + " potential issue(s) from a structure-only reading (no table sizes known).");
        return out.toString();
    }

    // ── analyzer (with tool evidence) ──────────────────────────────────────

    private String buildFindings(List<LlmMessage> messages) {
        JsonNode staticScan = toolResult(messages, "static_scan");
        JsonNode drift = toolResult(messages, "validate_entities");
        ArrayNode findings = mapper.createArrayNode();

        if (staticScan != null && staticScan.isArray()) {
            for (JsonNode f : staticScan) {
                findings.add(normalizeFinding(f));
            }
        }
        if (drift != null && drift.path("ran").asBoolean(false) && !drift.path("consistent").asBoolean(true)) {
            for (JsonNode item : drift.path("items")) {
                if (!anyMatch(findings, "ENTITY_SCHEMA_DRIFT", item.path("entity").asText())) {
                    ObjectNode f = mapper.createObjectNode();
                    f.put("ruleCode", "ENTITY_SCHEMA_DRIFT");
                    f.put("severity", "MEDIUM");
                    f.put("title", "Entity/schema drift: " + item.path("entity").asText());
                    f.put("targetObject", item.path("entity").asText());
                    f.put("summary", "The JPA mapping disagrees with the post-migration schema.");
                    f.put("evidence", "Hibernate-validate-equivalent: " + item.path("detail").asText());
                    f.put("confidence", 0.85);
                    findings.add(f);
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("findings", findings);
        out.put("summary", findings.isEmpty()
                ? "No production-safety defects detected in the candidate migration."
                : findings.size() + " potential issue(s) found; see per-finding evidence.");
        return out.toString();
    }

    // ── verifier ───────────────────────────────────────────────────────────

    private String buildVerdicts(List<LlmMessage> messages) {
        JsonNode analyzerFindings = analyzerFindings(messages);
        JsonNode sandboxRun = toolResult(messages, "run_candidate_migration");
        boolean sandboxRan = sandboxRun != null
                && (sandboxRun.path("candidateApplied").asBoolean(false)
                || sandboxRun.path("baselineApplied").asBoolean(false));

        ArrayNode verdicts = mapper.createArrayNode();
        if (analyzerFindings != null && analyzerFindings.isArray()) {
            for (JsonNode f : analyzerFindings) {
                ObjectNode v = mapper.createObjectNode();
                String rule = f.path("ruleCode").asText("");
                String evidence = f.path("evidence").asText("");
                boolean knownRule = isKnownRule(rule);
                boolean grounded = evidence.toLowerCase()
                        .matches(".*(sandbox|statement #|pg_|explain|reltuples|no create index|no index"
                                + "|check\\s*\\(|hibernate-validate|nullable|does not exist|type family).*");

                String verdict;
                String note;
                if (!knownRule) {
                    verdict = "REJECTED";
                    note = "rule code '" + rule + "' is not in the catalogue";
                } else if (grounded) {
                    verdict = "CONFIRMED";
                    note = "backed by tool output" + (sandboxRan ? " and a sandbox run" : "");
                } else {
                    verdict = "UNVERIFIED";
                    note = "plausible from the SQL structure but no tool output attached";
                }
                v.put("ruleCode", rule);
                v.put("targetObject", f.path("targetObject").asText(""));
                v.put("verdict", verdict);
                v.put("note", note);
                verdicts.add(v);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("verdicts", verdicts);
        return out.toString();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String userText(List<LlmMessage> messages) {
        return messages.stream().filter(m -> m.role() == LlmMessage.Role.USER)
                .map(LlmMessage::content).collect(Collectors.joining("\n"));
    }

    private String extractSql(String userText) {
        Matcher m = SQL_BLOCK.matcher(userText);
        return m.find() ? m.group(1).trim() : "";
    }

    private ObjectNode normalizeFinding(JsonNode f) {
        ObjectNode o = mapper.createObjectNode();
        o.put("ruleCode", f.path("ruleCode").asText());
        o.put("severity", f.path("severity").asText("MEDIUM"));
        o.put("title", f.path("title").asText());
        o.put("targetObject", f.path("targetObject").asText(""));
        o.put("summary", f.path("summary").asText(""));
        o.put("evidence", f.path("evidence").asText(""));
        if (f.hasNonNull("suggestedRewrite")) {
            o.put("suggestedRewrite", f.path("suggestedRewrite").asText());
        }
        o.put("confidence", f.path("confidence").asDouble(0.75));
        return o;
    }

    private boolean anyMatch(ArrayNode findings, String rule, String target) {
        for (JsonNode f : findings) {
            if (f.path("ruleCode").asText().equals(rule)
                    && f.path("targetObject").asText().equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private boolean isKnownRule(String rule) {
        try {
            RuleCode.valueOf(rule);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private JsonNode toolResult(List<LlmMessage> messages, String toolName) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage m = messages.get(i);
            if (m.role() == LlmMessage.Role.TOOL && toolName.equals(m.toolName())) {
                try {
                    return mapper.readTree(m.content());
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private JsonNode analyzerFindings(List<LlmMessage> messages) {
        Matcher m = ANALYZER_FINDINGS_BLOCK.matcher(userText(messages));
        if (m.find()) {
            try {
                JsonNode node = mapper.readTree(m.group(1).trim());
                return node.has("findings") ? node.get("findings") : node;
            } catch (Exception e) {
                log.warn("could not parse analyzer findings block: {}", e.getMessage());
            }
        }
        return null;
    }
}
