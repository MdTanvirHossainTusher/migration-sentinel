package com.migrationsentinel.model.enums;

/**
 * The catalogue of migration-safety defect classes the reviewer detects. Every finding
 * carries one of these, and every evaluation label is keyed by one, so precision/recall
 * can be computed per rule as well as overall.
 */
public enum RuleCode {

    /** DROP TABLE / DROP COLUMN / TRUNCATE — irreversible data loss. */
    DESTRUCTIVE_DDL,

    /** A new FOREIGN KEY column with no covering index — cascades and joins seq-scan the child. */
    UNINDEXED_FOREIGN_KEY,

    /** CREATE INDEX without CONCURRENTLY — holds a SHARE lock and blocks writes for the whole build. */
    NON_CONCURRENT_INDEX,

    /** ALTER TABLE ... SET NOT NULL on a populated table — full-table scan under ACCESS EXCLUSIVE. */
    NOT_NULL_WITHOUT_SAFE_BACKFILL,

    /** ADD COLUMN with a volatile / non-constant DEFAULT — rewrites every row (pre-PG11 semantics or expression default). */
    ADD_COLUMN_VOLATILE_DEFAULT,

    /** ALTER COLUMN TYPE that forces a table rewrite (e.g. varchar->int, int->bigint pre-PG). */
    TABLE_REWRITE_TYPE_CHANGE,

    /** A migration statement runs inside the implicit transaction but needs to run outside it (CREATE INDEX CONCURRENTLY, ALTER TYPE ... ADD VALUE). */
    UNSAFE_IN_TRANSACTION,

    /** The JPA entity model and the post-migration schema disagree (missing column, wrong nullability, wrong type). */
    ENTITY_SCHEMA_DRIFT,

    /** A NOT VALID constraint added but never VALIDATEd, or a CHECK added and validated in one step on a big table. */
    CONSTRAINT_VALIDATION_LOCK,

    /** Renaming a column/table that application code still references — breaks running pods during rollout. */
    BACKWARD_INCOMPATIBLE_RENAME,

    /** Missing migration entirely: an entity field with no corresponding column anywhere in the schema. */
    MISSING_MIGRATION,

    /** No defect — used by evaluation cases that must produce a clean report. */
    NONE
}
