package com.migrationsentinel.service.agent;

import com.migrationsentinel.payload.dto.MigrationInput;
import com.migrationsentinel.payload.dto.VerifiedFinding;
import com.migrationsentinel.service.rules.RuleCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Loads and fills the agent instruction files under {@code src/main/resources/prompts/}.
 * Keeping them as files (not string constants) makes them a citeable deliverable —
 * "the instructions that shape each agent" the hackathon asks for.
 */
@Slf4j
@Component
public class PromptLibrary {

    private final String analyzerTemplate = load("prompts/analyzer.md");
    private final String verifierTemplate = load("prompts/verifier.md");
    private final String baselineTemplate = load("prompts/baseline.md");

    public String analyzerSystemPrompt() {
        return analyzerTemplate.replace("{{RULE_CATALOGUE}}", ruleCatalogue());
    }

    public String verifierSystemPrompt() {
        return verifierTemplate;
    }

    public String baselineSystemPrompt() {
        return baselineTemplate;
    }

    public String reviewUserPrompt(MigrationInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("Review this migration for the engineer about to merge it.\n\n");
        sb.append("MIGRATION UNDER REVIEW (file: ")
                .append(input.filename() == null ? "candidate.sql" : input.filename())
                .append("):\n```sql\n").append(input.migrationSql().trim()).append("\n```\n\n");
        sb.append("BASELINE MIGRATIONS ALREADY APPLIED:\n");
        sb.append(input.hasBaseline() ? "```sql\n" + input.baselineSql().trim() + "\n```\n\n" : "none\n\n");
        sb.append("SEED / PLANNER-STAT SETUP APPLIED BEFORE THE CANDIDATE:\n");
        sb.append(input.hasSeed() ? "```sql\n" + input.seedSql().trim() + "\n```\n\n" : "none\n\n");
        sb.append("JPA ENTITY MAPPING:\n");
        sb.append(input.hasEntitySource() ? "```\n" + input.entitySource().trim() + "\n```\n\n" : "none provided\n\n");
        return sb.toString();
    }

    public String verifierUserPrompt(MigrationInput input, String analyzerFindingsJson) {
        return reviewUserPrompt(input)
                + "ANALYZER FINDINGS TO VERIFY:\n```json\n" + analyzerFindingsJson.trim() + "\n```\n";
    }

    private String ruleCatalogue() {
        return RuleCatalog.RULES.values().stream()
                .map(r -> "- **" + r.code() + "** (" + r.defaultSeverity() + "): " + r.why())
                .collect(Collectors.joining("\n"));
    }

    private String load(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("could not load prompt {}", path, e);
            return "";
        }
    }
}
