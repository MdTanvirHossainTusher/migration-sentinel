package com.migrationsentinel.model.enums;

/**
 * The pipeline configuration used for a review. These map one-to-one onto the
 * stages in docs/CHANGELOG_IMPROVEMENT.md so a single deployment can reproduce
 * every point on the improvement curve.
 */
public enum ReviewMode {

    /** Stage 0 — a single LLM prompt with the migration SQL pasted in. No tools. */
    BASELINE_PROMPT,

    /** Stage 1 — analyzer with read-only schema introspection tools. */
    ANALYZER_READ_ONLY,

    /** Stage 2 — analyzer plus the sandbox migration-execution tool (timing + lock evidence). */
    ANALYZER_WITH_SANDBOX,

    /** Stage 3 — stage 2 plus a verification pass that rejects findings with no tool evidence. */
    ANALYZER_VERIFIED,

    /** Stage 4 — the full two-agent split: a dedicated analyzer and a dedicated verifier. */
    ANALYZER_VERIFIER_SPLIT
}
