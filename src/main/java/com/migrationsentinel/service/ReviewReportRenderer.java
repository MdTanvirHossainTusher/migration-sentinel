package com.migrationsentinel.service;

import com.migrationsentinel.model.enums.FindingVerdict;
import com.migrationsentinel.model.enums.Severity;
import com.migrationsentinel.payload.dto.MigrationInput;
import com.migrationsentinel.payload.dto.SandboxRunResult;
import com.migrationsentinel.payload.dto.VerifiedFinding;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Renders the Markdown review report an engineer pastes into a PR. Deliberately plain:
 * a verdict line, a table, then one evidence block per finding. Nothing about it should
 * read as generated prose.
 */
@Component
public class ReviewReportRenderer {

    public String render(MigrationInput input, List<VerifiedFinding> findings, SandboxRunResult sandboxRun,
                         boolean sandboxUsed) {
        List<VerifiedFinding> sorted = findings.stream()
                .sorted(Comparator.comparingInt(f -> severityRank(f.finding().severity())))
                .toList();

        StringBuilder md = new StringBuilder();
        md.append("# Migration review: ")
                .append(input.filename() == null ? "candidate.sql" : input.filename())
                .append("\n\n");

        long high = count(sorted, Severity.HIGH);
        long medium = count(sorted, Severity.MEDIUM);
        long low = count(sorted, Severity.LOW);

        SandboxRunResult.BaselineReplay baseline =
                sandboxRun == null ? SandboxRunResult.BaselineReplay.none() : sandboxRun.baselineOrNone();

        if (sorted.isEmpty() && sandboxUsed) {
            md.append("**Verdict: safe to merge.** No production-safety defects found — the candidate ");
            if (baseline.filesTotal() > 1) {
                md.append("applied cleanly on top of ").append(baseline.filesTotal())
                        .append(" prior migrations, with the seeded data.\n\n");
            } else {
                md.append("applied cleanly in a sandbox with the seeded data.\n\n");
            }
        } else if (sorted.isEmpty()) {
            // "Safe to merge" is the one thing a structure-only pass has not earned the right
            // to say: nothing ran, so an empty list means unchecked, not clean. Saying it
            // anyway is precisely the false confidence this tool exists to remove.
            md.append("**Verdict: not established.** Nothing was measured — no sandbox ran, so this is a "
                    + "read of the SQL text alone. An empty findings list here means *unchecked*, "
                    + "not *safe*.\n\n");
        } else if (high > 0) {
            md.append("**Verdict: do not merge as-is.** ")
                    .append(high).append(" high-severity issue(s)");
            if (medium + low > 0) {
                md.append(", plus ").append(medium + low).append(" lower-severity");
            }
            md.append(".\n\n");
        } else {
            md.append("**Verdict: needs a decision.** ")
                    .append(medium).append(" medium and ").append(low).append(" low-severity issue(s), no blockers.\n\n");
        }

        if (baseline.failed()) {
            // Loud, and above the findings: with the history only half applied the candidate
            // was measured against a schema that exists nowhere, so nothing below is evidence.
            md.append("> **The prior migrations did not finish replaying, so this review is not grounded.**\n>\n");
            md.append("> ").append(baseline.describeFailure()).append("\n>\n");
            if (baseline.failedStatement() != null) {
                md.append("> Failing statement: `").append(baseline.failedStatement()).append("`\n>\n");
            }
            md.append("> Fix the history (a missing extension, a role, or a file the sandbox cannot run "
                    + "unchanged) and re-run, or the row counts and lock modes below are absent rather "
                    + "than reassuring.\n\n");
        } else if (baseline.filesTotal() > 1) {
            md.append("Replayed ").append(baseline.filesTotal()).append(" prior migration files (")
                    .append(baseline.statementsApplied()).append(" statements) before the candidate.\n\n");
        }

        md.append("| # | Severity | Rule | Object | Verdict |\n|---|---|---|---|---|\n");
        int i = 1;
        for (VerifiedFinding f : sorted) {
            md.append("| ").append(i++).append(" | ")
                    .append(f.finding().severity()).append(" | `")
                    .append(f.finding().ruleCode()).append("` | `")
                    .append(nullToDash(f.finding().targetObject())).append("` | ")
                    .append(f.verdict()).append(" |\n");
        }
        md.append("\n");

        i = 1;
        for (VerifiedFinding vf : sorted) {
            var f = vf.finding();
            md.append("## ").append(i++).append(". ").append(f.title()).append("\n\n");
            md.append("- **Severity:** ").append(f.severity())
                    .append("  |  **Rule:** `").append(f.ruleCode()).append("`")
                    .append("  |  **Verifier:** ").append(vf.verdict());
            if (vf.verdict() == FindingVerdict.UNVERIFIED) {
                md.append(" _(kept but not proven at sandbox scale)_");
            }
            md.append("\n\n");
            md.append(f.summary()).append("\n\n");
            if (f.evidence() != null && !f.evidence().isBlank()) {
                md.append("**Evidence**\n\n```\n").append(f.evidence().trim()).append("\n```\n\n");
            }
            if (vf.verifierNote() != null && !vf.verifierNote().isBlank()) {
                md.append("**Verifier note:** ").append(vf.verifierNote()).append("\n\n");
            }
            if (f.suggestedRewrite() != null && !f.suggestedRewrite().isBlank()) {
                md.append("**Suggested rewrite** (copy into the migration yourself — Sentinel does not edit files)\n\n");
                md.append("```sql\n").append(f.suggestedRewrite().trim()).append("\n```\n\n");
            }
        }

        if (sandboxUsed && sandboxRun != null) {
            md.append("---\n\n## Sandbox run\n\n");
            md.append("- Prior migrations replayed: ").append(baseline.filesApplied()).append(" of ")
                    .append(baseline.filesTotal()).append(" file(s), ")
                    .append(baseline.statementsApplied()).append(" statement(s)\n");
            md.append("- Baseline applied: ").append(sandboxRun.baselineApplied()).append("\n");
            md.append("- Candidate applied: ").append(sandboxRun.candidateApplied())
                    .append(sandboxRun.timedOut() ? " (timed out)" : "").append("\n");
            md.append("- Candidate wall time: ").append(sandboxRun.candidateDurationMs()).append(" ms\n");
            if (sandboxRun.failureMessage() != null) {
                md.append("- Failure: `").append(sandboxRun.failureMessage().trim()).append("`\n");
            }
            if (!sandboxRun.statements().isEmpty()) {
                md.append("\n| stmt | ok | ms | strongest lock |\n|---|---|---|---|\n");
                sandboxRun.statements().forEach(s -> md.append("| ").append(s.index())
                        .append(" | ").append(s.ok() ? "yes" : "no")
                        .append(" | ").append(s.durationMs())
                        .append(" | ").append(s.strongestLock()).append(" |\n"));
            }
            md.append("\n");
        }

        md.append("---\n\n_Generated by Migration Sentinel. DDL ran only in a disposable container; "
                + "no user database was reachable. See SAFETY_MODEL.md._\n");
        return md.toString();
    }

    private int severityRank(Severity s) {
        return switch (s) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
        };
    }

    private long count(List<VerifiedFinding> findings, Severity severity) {
        return findings.stream().filter(f -> f.finding().severity() == severity).count();
    }

    private String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
