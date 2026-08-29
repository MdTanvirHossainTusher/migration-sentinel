package com.migrationsentinel.service.rules;

import com.migrationsentinel.model.enums.RuleCode;
import com.migrationsentinel.model.enums.Severity;

import java.util.Map;

/**
 * Human-readable metadata for every {@link RuleCode}: what it means, why it hurts in
 * production, and the shape of a safe rewrite. Used both to ground the LLM analyzer's
 * prompt and to fill in the static scanner's findings.
 */
public final class RuleCatalog {

    private RuleCatalog() {
    }

    public record Rule(RuleCode code, Severity defaultSeverity, String title, String why, String remediation) {
    }

    public static final Map<RuleCode, Rule> RULES = Map.ofEntries(
            Map.entry(RuleCode.DESTRUCTIVE_DDL, new Rule(RuleCode.DESTRUCTIVE_DDL, Severity.HIGH,
                    "Destructive DDL drops data irreversibly",
                    "DROP TABLE / DROP COLUMN / TRUNCATE cannot be rolled back once the deploy completes and the "
                            + "old rows are gone. If application code still reads the object, running pods start erroring "
                            + "the moment the migration commits.",
                    "Ship the drop one release after the code that stopped using the object. Rename to "
                            + "*_deprecated first, wait a release, then drop. Keep a data export.")),

            Map.entry(RuleCode.UNINDEXED_FOREIGN_KEY, new Rule(RuleCode.UNINDEXED_FOREIGN_KEY, Severity.MEDIUM,
                    "New foreign key has no covering index",
                    "Postgres does not auto-create an index for the referencing side of a FK. Every DELETE or key "
                            + "update on the parent then sequentially scans the child table, and ORM lazy-loads across "
                            + "the relationship do full scans.",
                    "Add CREATE INDEX CONCURRENTLY on the FK column(s) in the same migration set.")),

            Map.entry(RuleCode.NON_CONCURRENT_INDEX, new Rule(RuleCode.NON_CONCURRENT_INDEX, Severity.HIGH,
                    "CREATE INDEX without CONCURRENTLY blocks writes",
                    "A plain CREATE INDEX takes a SHARE lock on the table for the entire build. On a large table "
                            + "that is minutes of blocked INSERT/UPDATE/DELETE — an effective write outage.",
                    "Use CREATE INDEX CONCURRENTLY, and run it outside a transaction (Flyway: a separate migration "
                            + "with executeInTransaction=false or a repeatable script).")),

            Map.entry(RuleCode.NOT_NULL_WITHOUT_SAFE_BACKFILL, new Rule(RuleCode.NOT_NULL_WITHOUT_SAFE_BACKFILL, Severity.HIGH,
                    "SET NOT NULL scans the whole table under ACCESS EXCLUSIVE",
                    "ALTER TABLE ... ALTER COLUMN ... SET NOT NULL must verify every existing row. It holds "
                            + "ACCESS EXCLUSIVE for the duration of that scan, blocking reads and writes. Instant on an "
                            + "empty table, an outage on a 50M-row one.",
                    "Add a CHECK (col IS NOT NULL) NOT VALID, backfill in batches, VALIDATE CONSTRAINT (SHARE UPDATE "
                            + "EXCLUSIVE only), then SET NOT NULL which now uses the validated constraint and skips the scan.")),

            Map.entry(RuleCode.ADD_COLUMN_VOLATILE_DEFAULT, new Rule(RuleCode.ADD_COLUMN_VOLATILE_DEFAULT, Severity.HIGH,
                    "ADD COLUMN with a volatile default rewrites every row",
                    "A constant default is metadata-only since PG11, but a volatile expression (now(), "
                            + "gen_random_uuid(), a sequence) still rewrites the entire table under ACCESS EXCLUSIVE.",
                    "Add the column with no default, backfill in batches, then SET DEFAULT for future rows.")),

            Map.entry(RuleCode.TABLE_REWRITE_TYPE_CHANGE, new Rule(RuleCode.TABLE_REWRITE_TYPE_CHANGE, Severity.HIGH,
                    "ALTER COLUMN TYPE forces a full table rewrite",
                    "Most type changes (varchar->int, numeric precision down, int->bigint on older PG) rewrite "
                            + "every row and every dependent index under ACCESS EXCLUSIVE.",
                    "Add a new column of the target type, backfill, swap in application code, drop the old column "
                            + "in a later release.")),

            Map.entry(RuleCode.UNSAFE_IN_TRANSACTION, new Rule(RuleCode.UNSAFE_IN_TRANSACTION, Severity.MEDIUM,
                    "Statement cannot run inside Flyway's implicit transaction",
                    "CREATE INDEX CONCURRENTLY and ALTER TYPE ... ADD VALUE (pre-PG12) error out or silently lose "
                            + "their concurrency guarantee when wrapped in a transaction, which Flyway does by default.",
                    "Put the statement in its own migration with executeInTransaction=false.")),

            Map.entry(RuleCode.ENTITY_SCHEMA_DRIFT, new Rule(RuleCode.ENTITY_SCHEMA_DRIFT, Severity.MEDIUM,
                    "JPA entity model disagrees with the post-migration schema",
                    "Hibernate ddl-auto=validate fails the application boot when an entity maps a column that does "
                            + "not exist, or the nullability/type differs. The deploy rolls back after the migration "
                            + "already committed.",
                    "Align the migration and the entity in the same change. Run Hibernate validate in CI.")),

            Map.entry(RuleCode.CONSTRAINT_VALIDATION_LOCK, new Rule(RuleCode.CONSTRAINT_VALIDATION_LOCK, Severity.MEDIUM,
                    "Constraint added and validated in one step locks the table",
                    "ADD CONSTRAINT ... (without NOT VALID) or ADD FOREIGN KEY validates every existing row while "
                            + "holding a strong lock. NOT VALID + a later VALIDATE CONSTRAINT splits the cost and only "
                            + "takes SHARE UPDATE EXCLUSIVE for the validation.",
                    "Add the constraint NOT VALID, then VALIDATE CONSTRAINT in a separate migration.")),

            Map.entry(RuleCode.BACKWARD_INCOMPATIBLE_RENAME, new Rule(RuleCode.BACKWARD_INCOMPATIBLE_RENAME, Severity.HIGH,
                    "Rename breaks pods still running the old code during rollout",
                    "During a rolling deploy the old and new application versions run at once. A column/table rename "
                            + "makes every query from the old pods fail until they are all replaced.",
                    "Expand/contract: add the new name, write both, migrate reads, then drop the old name a "
                            + "release later.")),

            Map.entry(RuleCode.MISSING_MIGRATION, new Rule(RuleCode.MISSING_MIGRATION, Severity.MEDIUM,
                    "Entity field has no column anywhere in the schema",
                    "The entity references a column that neither the baseline nor the candidate migration creates. "
                            + "Hibernate validate fails at boot.",
                    "Add the missing column in this migration."))
    );

    public static Rule get(RuleCode code) {
        return RULES.get(code);
    }
}
