package com.migrationsentinel.service.rules;

import com.migrationsentinel.model.enums.RuleCode;
import com.migrationsentinel.model.enums.Severity;
import com.migrationsentinel.payload.dto.ProposedFinding;
import com.migrationsentinel.payload.dto.SchemaDriftReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic migration-safety rules. This is the reviewer's grounded backbone: every
 * finding it emits is traceable to a parsed statement and, where the sandbox ran, to a
 * concrete row estimate or index fact. The LLM analyzer runs on top of this — it can add
 * nuance and catch cases the regexes miss, and the verifier can drop anything the LLM
 * invents that the scanner and sandbox do not support.
 */
@Component
@RequiredArgsConstructor
public class StaticRuleScanner {

    private static final Set<String> REWRITE_TYPE_HINTS = Set.of(
            "int", "integer", "bigint", "smallint", "numeric", "decimal", "boolean", "bool", "uuid", "date", "timestamp");

    private final DdlParser ddlParser;

    /**
     * Flyway runs a migration inside one transaction unless the file opts out with
     * {@code -- flyway:executeInTransaction=false} (or a matching config). Statements like
     * CREATE INDEX CONCURRENTLY are only safe when that opt-out is present.
     */
    public static boolean runsInSingleTransaction(String candidateSql) {
        if (candidateSql == null) {
            return true;
        }
        String lower = candidateSql.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return !lower.contains("executeintransaction=false") && !lower.contains("executeintransaction:false");
    }

    public List<ProposedFinding> scan(String candidateSql, SchemaFacts facts, SchemaDriftReport drift) {
        return scan(candidateSql, facts, drift, runsInSingleTransaction(candidateSql));
    }

    public List<ProposedFinding> scan(String candidateSql, SchemaFacts facts, SchemaDriftReport drift, boolean singleTxn) {
        List<ParsedStatement> statements = ddlParser.parse(candidateSql);
        List<ProposedFinding> findings = new ArrayList<>();
        List<String> fkColumnsAdded = new ArrayList<>();

        for (ParsedStatement st : statements) {
            switch (st.kind()) {
                case DROP_TABLE, DROP_COLUMN, TRUNCATE -> findings.add(destructive(st));
                case CREATE_INDEX -> {
                    if (!st.concurrently()) {
                        findings.add(nonConcurrentIndex(st, facts));
                    } else if (singleTxn) {
                        findings.add(unsafeInTransaction(st, "CREATE INDEX CONCURRENTLY"));
                    }
                }
                case SET_NOT_NULL -> {
                    ProposedFinding f = setNotNull(st, facts, candidateSql);
                    if (f != null) {
                        findings.add(f);
                    }
                }
                case ADD_COLUMN -> {
                    if (isVolatileDefault(st.defaultExpr())) {
                        findings.add(volatileDefault(st, facts));
                    }
                }
                case ALTER_COLUMN_TYPE -> {
                    if (forcesRewrite(st.newType())) {
                        findings.add(typeChange(st, facts));
                    }
                }
                case ADD_FOREIGN_KEY -> {
                    fkColumnsAdded.addAll(st.columns());
                    if (!st.notValid()) {
                        findings.add(constraintValidationLock(st, "FOREIGN KEY"));
                    }
                }
                case ADD_CHECK_CONSTRAINT, ADD_UNIQUE_CONSTRAINT -> {
                    if (!st.notValid() && facts.isLarge(st.table())) {
                        findings.add(constraintValidationLock(st, "constraint"));
                    }
                }
                case ALTER_TYPE_ADD_VALUE -> {
                    if (singleTxn) {
                        findings.add(unsafeInTransaction(st, "ALTER TYPE ... ADD VALUE"));
                    }
                }
                case RENAME_COLUMN, RENAME_TABLE -> findings.add(rename(st));
                default -> {
                    // no deterministic rule
                }
            }
        }

        // A new FK column (added via ADD COLUMN ... REFERENCES or a separate ADD CONSTRAINT)
        // with no covering index once the whole migration set is applied.
        for (ParsedStatement st : statements) {
            if (st.kind() == ParsedStatement.Kind.ADD_COLUMN
                    && st.normalized().toLowerCase(Locale.ROOT).contains("references")) {
                fkColumnsAdded.addAll(st.columns());
            }
        }
        for (ParsedStatement st : statements) {
            if (st.kind() != ParsedStatement.Kind.ADD_FOREIGN_KEY && !inlineFk(st)) {
                continue;
            }
            for (String col : st.columns()) {
                if (facts.sandboxRan() && facts.columnCoveredByIndex(st.table(), col)) {
                    continue;
                }
                boolean indexInSameSet = statements.stream().anyMatch(other ->
                        other.kind() == ParsedStatement.Kind.CREATE_INDEX
                                && st.table() != null && st.table().equalsIgnoreCase(other.table())
                                && !other.columns().isEmpty() && other.columns().get(0).equalsIgnoreCase(col));
                if (!indexInSameSet) {
                    findings.add(unindexedFk(st, col, facts));
                }
            }
        }

        if (drift != null && drift.ran() && !drift.consistent()) {
            for (SchemaDriftReport.DriftItem item : drift.items()) {
                findings.add(entityDrift(item));
            }
        }
        return dedupe(findings);
    }

    // ── individual rules ────────────────────────────────────────────────────

    private ProposedFinding destructive(ParsedStatement st) {
        RuleCatalog.Rule r = RuleCatalog.get(RuleCode.DESTRUCTIVE_DDL);
        String object = st.kind() == ParsedStatement.Kind.DROP_COLUMN
                ? st.table() + "." + first(st.columns()) : st.table();
        return new ProposedFinding(RuleCode.DESTRUCTIVE_DDL, Severity.HIGH,
                st.kind() + " on " + object,
                object, r.why(),
                "statement #" + st.index() + ": " + st.normalized(),
                st.kind() == ParsedStatement.Kind.DROP_COLUMN
                        ? "-- defer the drop; first stop writing the column, ship, then:\n" + st.raw()
                        : "-- rename to " + object + "_deprecated for one release, then drop",
                0.95);
    }

    private ProposedFinding nonConcurrentIndex(ParsedStatement st, SchemaFacts facts) {
        RuleCatalog.Rule r = RuleCatalog.get(RuleCode.NON_CONCURRENT_INDEX);
        long rows = facts.rowEstimate(st.table());
        Severity sev = rows < 0 || rows >= facts.largeTableThreshold() / 10 ? Severity.HIGH : Severity.MEDIUM;
        String ev = rows >= 0
                ? "sandbox: " + st.table() + " holds ~" + rows + " rows; the SHARE lock is held for the whole build"
                : "sandbox did not measure " + st.table() + "; assume production scale";
        return new ProposedFinding(RuleCode.NON_CONCURRENT_INDEX, sev,
                "CREATE INDEX without CONCURRENTLY on " + st.table(),
                st.table(), r.why(), ev,
                st.raw().replaceFirst("(?i)create\\s+index", "CREATE INDEX CONCURRENTLY")
                        + "\n-- run in its own migration with executeInTransaction=false", 0.9);
    }

    private ProposedFinding setNotNull(ParsedStatement st, SchemaFacts facts, String candidateSql) {
        RuleCatalog.Rule r = RuleCatalog.get(RuleCode.NOT_NULL_WITHOUT_SAFE_BACKFILL);
        long rows = facts.rowEstimate(st.table());
        boolean hasNotValidCheck = candidateSql.toLowerCase(Locale.ROOT)
                .matches("(?s).*check\\s*\\(\\s*" + java.util.regex.Pattern.quote(first(st.columns()))
                        + "\\s+is\\s+not\\s+null\\s*\\)\\s+not\\s+valid.*");
        if (rows == 0 && facts.sandboxRan()) {
            // Verified empty in the sandbox: SET NOT NULL is instant and safe here.
            return null;
        }
        if (hasNotValidCheck) {
            // The engineer used the safe pattern: CHECK (col IS NOT NULL) NOT VALID means
            // SET NOT NULL reuses the validated constraint and skips the full scan.
            return null;
        }
        Severity sev;
        String ev;
        if (rows < 0) {
            sev = Severity.MEDIUM;
            ev = "sandbox did not measure " + st.table() + " row count; safe only if the table is small in prod";
        } else if (rows >= facts.largeTableThreshold()) {
            sev = Severity.HIGH;
            ev = "sandbox: " + st.table() + " holds " + rows + " rows (>= " + facts.largeTableThreshold()
                    + " threshold). SET NOT NULL scans all of them under ACCESS EXCLUSIVE.";
        } else if (rows == 0) {
            sev = Severity.LOW;
            ev = "sandbox: " + st.table() + " is empty in this scenario; SET NOT NULL is instant here, but the same "
                    + "statement is an outage on a populated table.";
        } else {
            sev = Severity.MEDIUM;
            ev = "sandbox: " + st.table() + " holds " + rows + " rows; scan time scales linearly with table size.";
        }
        if (hasNotValidCheck) {
            sev = Severity.LOW;
            ev += " A CHECK (... IS NOT NULL) NOT VALID is present, which lets SET NOT NULL skip the scan.";
        }
        return new ProposedFinding(RuleCode.NOT_NULL_WITHOUT_SAFE_BACKFILL, sev,
                "SET NOT NULL on " + st.table() + "." + first(st.columns()),
                st.table() + "." + first(st.columns()), r.why(), ev,
                "ALTER TABLE " + st.table() + " ADD CONSTRAINT " + st.table() + "_" + first(st.columns())
                        + "_nn CHECK (" + first(st.columns()) + " IS NOT NULL) NOT VALID;\n"
                        + "-- backfill in batches --\n"
                        + "ALTER TABLE " + st.table() + " VALIDATE CONSTRAINT " + st.table() + "_"
                        + first(st.columns()) + "_nn;\n"
                        + "ALTER TABLE " + st.table() + " ALTER COLUMN " + first(st.columns()) + " SET NOT NULL;",
                hasNotValidCheck ? 0.5 : 0.85);
    }

    private ProposedFinding volatileDefault(ParsedStatement st, SchemaFacts facts) {
        RuleCatalog.Rule r = RuleCatalog.get(RuleCode.ADD_COLUMN_VOLATILE_DEFAULT);
        long rows = facts.rowEstimate(st.table());
        return new ProposedFinding(RuleCode.ADD_COLUMN_VOLATILE_DEFAULT,
                rows >= facts.largeTableThreshold() || rows < 0 ? Severity.HIGH : Severity.MEDIUM,
                "ADD COLUMN " + first(st.columns()) + " DEFAULT " + st.defaultExpr() + " on " + st.table(),
                st.table() + "." + first(st.columns()), r.why(),
                (rows >= 0 ? "sandbox: " + st.table() + " holds " + rows + " rows; " : "")
                        + "default expression '" + st.defaultExpr() + "' is volatile and rewrites every row",
                "ALTER TABLE " + st.table() + " ADD COLUMN " + first(st.columns()) + " <type>;\n"
                        + "-- backfill in batches --\n"
                        + "ALTER TABLE " + st.table() + " ALTER COLUMN " + first(st.columns())
                        + " SET DEFAULT " + st.defaultExpr() + ";", 0.8);
    }

    private ProposedFinding typeChange(ParsedStatement st, SchemaFacts facts) {
        RuleCatalog.Rule r = RuleCatalog.get(RuleCode.TABLE_REWRITE_TYPE_CHANGE);
        long rows = facts.rowEstimate(st.table());
        return new ProposedFinding(RuleCode.TABLE_REWRITE_TYPE_CHANGE,
                rows >= facts.largeTableThreshold() || rows < 0 ? Severity.HIGH : Severity.MEDIUM,
                "ALTER COLUMN TYPE on " + st.table() + "." + first(st.columns()) + " -> " + st.newType(),
                st.table() + "." + first(st.columns()), r.why(),
                (rows >= 0 ? "sandbox: " + st.table() + " holds " + rows + " rows; " : "")
                        + "changing to '" + st.newType() + "' rewrites the table and its indexes under ACCESS EXCLUSIVE",
                "-- add " + first(st.columns()) + "_new " + st.newType() + ", backfill, swap, drop old", 0.75);
    }

    private ProposedFinding constraintValidationLock(ParsedStatement st, String what) {
        RuleCatalog.Rule r = RuleCatalog.get(RuleCode.CONSTRAINT_VALIDATION_LOCK);
        return new ProposedFinding(RuleCode.CONSTRAINT_VALIDATION_LOCK, Severity.MEDIUM,
                "ADD " + what + " validated in one step on " + st.table(),
                st.table(), r.why(),
                "statement #" + st.index() + ": " + st.normalized() + " — no NOT VALID clause",
                st.raw().replaceFirst(";?\\s*$", " NOT VALID;")
                        + "\nALTER TABLE " + st.table() + " VALIDATE CONSTRAINT <name>;", 0.7);
    }

    private ProposedFinding unsafeInTransaction(ParsedStatement st, String what) {
        RuleCatalog.Rule r = RuleCatalog.get(RuleCode.UNSAFE_IN_TRANSACTION);
        return new ProposedFinding(RuleCode.UNSAFE_IN_TRANSACTION, Severity.MEDIUM,
                what + " runs inside Flyway's implicit transaction",
                st.table(), r.why(),
                "statement #" + st.index() + " is transactional by default and this statement forbids that",
                "-- move to its own migration file with executeInTransaction=false", 0.8);
    }

    private ProposedFinding unindexedFk(ParsedStatement st, String col, SchemaFacts facts) {
        RuleCatalog.Rule r = RuleCatalog.get(RuleCode.UNINDEXED_FOREIGN_KEY);
        String ev = facts.sandboxRan()
                ? "sandbox: after the full migration, " + st.table() + "." + col + " has no index whose leading column is "
                + col + " (checked via pg_index)"
                : "no CREATE INDEX on " + st.table() + "(" + col + ") anywhere in the migration set";
        return new ProposedFinding(RuleCode.UNINDEXED_FOREIGN_KEY, Severity.MEDIUM,
                "Foreign key " + st.table() + "." + col + " has no covering index",
                st.table() + "." + col, r.why(), ev,
                "CREATE INDEX CONCURRENTLY ix_" + st.table() + "_" + col + " ON " + st.table() + " (" + col + ");", 0.8);
    }

    private ProposedFinding rename(ParsedStatement st) {
        RuleCatalog.Rule r = RuleCatalog.get(RuleCode.BACKWARD_INCOMPATIBLE_RENAME);
        return new ProposedFinding(RuleCode.BACKWARD_INCOMPATIBLE_RENAME, Severity.HIGH,
                st.kind() + " on " + st.table(),
                st.table(), r.why(),
                "statement #" + st.index() + ": " + st.normalized()
                        + " — old pods keep querying the old name during a rolling deploy",
                "-- expand/contract: add new name, dual-write, migrate reads, drop old name next release", 0.7);
    }

    private ProposedFinding entityDrift(SchemaDriftReport.DriftItem item) {
        RuleCatalog.Rule r = RuleCatalog.get(RuleCode.ENTITY_SCHEMA_DRIFT);
        return new ProposedFinding(RuleCode.ENTITY_SCHEMA_DRIFT, Severity.MEDIUM,
                "Entity/schema drift: " + item.entity(),
                item.entity(), r.why(),
                "Hibernate validate against the post-migration sandbox schema: " + item.detail(),
                "-- align the migration with " + item.entity() + " (add/adjust the column)", 0.85);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private boolean inlineFk(ParsedStatement st) {
        return st.kind() == ParsedStatement.Kind.ADD_COLUMN
                && st.normalized().toLowerCase(Locale.ROOT).contains("references");
    }

    private boolean isVolatileDefault(String expr) {
        if (expr == null) {
            return false;
        }
        String e = expr.toLowerCase(Locale.ROOT);
        return e.contains("now()") || e.contains("current_timestamp") || e.contains("clock_timestamp")
                || e.contains("gen_random_uuid") || e.contains("uuid_generate") || e.contains("nextval")
                || e.contains("random(");
    }

    private boolean forcesRewrite(String newType) {
        if (newType == null) {
            return false;
        }
        String t = newType.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return REWRITE_TYPE_HINTS.stream().anyMatch(t::startsWith);
    }

    private String first(List<String> list) {
        return list.isEmpty() ? "?" : list.get(0);
    }

    private List<ProposedFinding> dedupe(List<ProposedFinding> findings) {
        List<ProposedFinding> out = new ArrayList<>();
        for (ProposedFinding f : findings) {
            boolean dup = out.stream().anyMatch(o -> o.ruleCode() == f.ruleCode()
                    && java.util.Objects.equals(o.targetObject(), f.targetObject()));
            if (!dup) {
                out.add(f);
            }
        }
        return out;
    }
}
