package com.migrationsentinel.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.migrationsentinel.payload.dto.MigrationInput;
import com.migrationsentinel.service.support.AgentJsonMapper;
import com.migrationsentinel.payload.dto.SandboxRunResult;
import com.migrationsentinel.payload.dto.SchemaDriftReport;
import com.migrationsentinel.payload.dto.TableStat;
import com.migrationsentinel.service.rules.SchemaFacts;
import com.migrationsentinel.service.rules.StaticRuleScanner;
import com.migrationsentinel.service.sandbox.JpaMappingValidator;
import com.migrationsentinel.service.sandbox.MigrationReplayer;
import com.migrationsentinel.service.sandbox.SandboxSession;
import com.migrationsentinel.service.sandbox.SchemaIntrospector;
import com.migrationsentinel.util.SqlScript;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's tool layer for one review. Read tools query the live sandbox; the single
 * write tool ({@code run_candidate_migration}) replays the baseline + seed + candidate,
 * caches the result, and is idempotent. The agent never sees a connection string.
 */
@Slf4j
public class Toolbox implements AutoCloseable {

    private final MigrationInput input;
    private final SandboxSession sandbox;
    private final long largeTableThreshold;
    private final boolean applyCandidate;

    private final AgentJsonMapper mapper;
    private final SchemaIntrospector introspector;
    private final MigrationReplayer replayer;
    private final JpaMappingValidator mappingValidator;
    private final StaticRuleScanner staticScanner;

    private SandboxRunResult cachedRun;
    private SchemaDriftReport cachedDrift;
    private boolean baselineOnlyApplied;

    Toolbox(MigrationInput input, SandboxSession sandbox, long largeTableThreshold, boolean applyCandidate,
            AgentJsonMapper mapper, SchemaIntrospector introspector, MigrationReplayer replayer,
            JpaMappingValidator mappingValidator, StaticRuleScanner staticScanner) {
        this.input = input;
        this.sandbox = sandbox;
        this.largeTableThreshold = largeTableThreshold;
        this.applyCandidate = applyCandidate;
        this.mapper = mapper;
        this.introspector = introspector;
        this.replayer = replayer;
        this.mappingValidator = mappingValidator;
        this.staticScanner = staticScanner;
    }

    public boolean sandboxAvailable() {
        return sandbox != null;
    }

    public SandboxRunResult ensureCandidateRun() {
        if (cachedRun == null) {
            if (sandbox == null) {
                cachedRun = SandboxRunResult.unavailable("sandbox disabled or Docker unavailable");
            } else if (applyCandidate) {
                cachedRun = replayer.replay(sandbox, input.baselineFilesOrEmpty(), input.baselineSql(),
                        input.seedSql(), input.migrationSql(), input.targetSchema());
            } else {
                cachedRun = replayer.replayBaselineOnly(sandbox, input.baselineFilesOrEmpty(),
                        input.baselineSql(), input.seedSql(), input.targetSchema());
            }
        }
        return cachedRun;
    }

    public SchemaFacts facts() {
        SandboxRunResult run = ensureCandidateRun();
        return SchemaFacts.from(run, largeTableThreshold);
    }

    public SchemaDriftReport drift() {
        if (cachedDrift == null) {
            cachedDrift = computeDrift();
        }
        return cachedDrift;
    }

    private SchemaDriftReport computeDrift() {
        if (!input.hasEntitySource()) {
            return SchemaDriftReport.notRun("no entity source supplied");
        }
        if (sandbox == null) {
            return SchemaDriftReport.notRun("sandbox unavailable — cannot compare against a live schema");
        }
        if (!applyCandidate) {
            return SchemaDriftReport.notRun("read-only mode: the candidate migration was not applied");
        }
        ensureBaselineForDrift();
        try (Connection c = sandbox.open()) {
            Map<String, TableStat> schema = introspector.snapshot(c);
            return mappingValidator.validate(input.entitySource(), schema);
        } catch (Exception ex) {
            return SchemaDriftReport.notRun("drift check error: " + ex.getMessage());
        }
    }

    /** For the read-only mode: apply baseline+candidate so introspection sees the final schema. */
    private void ensureBaselineForDrift() {
        if (cachedRun != null || baselineOnlyApplied) {
            return;
        }
        ensureCandidateRun();
    }

    public List<ToolSpec> specs(boolean includeSandboxRun) {
        List<ToolSpec> specs = new ArrayList<>();

        specs.add(new ToolSpec("list_tables",
                "List every user table currently in the sandbox schema.",
                ToolSpec.objectSchema(Map.of()),
                args -> mapper.writeValueAsString(withConn(introspector::userTables))));

        specs.add(new ToolSpec("describe_table",
                "Columns (name, type, nullable, default), indexes and foreign keys of one table, "
                        + "with the pg_class row estimate and exact count.",
                ToolSpec.objectSchema(Map.of("table", ToolSpec.stringProp("table name")), "table"),
                args -> {
                    String table = args.path("table").asText();
                    return mapper.writeValueAsString(withConn(c -> introspector.tableStat(c, table)));
                }));

        specs.add(new ToolSpec("estimate_rows",
                "The planner's row estimate for a table (pg_class.reltuples). Use this to judge lock/scan cost.",
                ToolSpec.objectSchema(Map.of("table", ToolSpec.stringProp("table name")), "table"),
                args -> {
                    String table = args.path("table").asText();
                    long est = withConn(c -> introspector.estimatedRows(c, table));
                    long exact = withConn(c -> introspector.exactRows(c, table));
                    return mapper.writeValueAsString(Map.of("table", table, "estimated_rows", est, "exact_rows", exact));
                }));

        specs.add(new ToolSpec("explain",
                "Run EXPLAIN on a query against the post-migration sandbox schema and return the plan.",
                ToolSpec.objectSchema(Map.of("query", ToolSpec.stringProp("a SELECT/DELETE/UPDATE statement")), "query"),
                args -> {
                    String q = args.path("query").asText();
                    return mapper.writeValueAsString(Map.of("query", q, "plan", withConn(c -> introspector.explain(c, q))));
                }));

        specs.add(new ToolSpec("static_scan",
                "Run the deterministic migration-safety rule scanner over the candidate SQL and return its "
                        + "findings. Treat these as grounded hints, not the final answer.",
                ToolSpec.objectSchema(Map.of()),
                args -> mapper.writeValueAsString(
                        staticScanner.scan(input.migrationSql(), facts(), drift()))));

        if (input.hasEntitySource()) {
            specs.add(new ToolSpec("validate_entities",
                    "Hibernate-validate-equivalent: check the supplied JPA mapping against the live "
                            + "post-migration schema (missing columns, nullability, type family).",
                    ToolSpec.objectSchema(Map.of()),
                    args -> mapper.writeValueAsString(drift())));
        }

        if (includeSandboxRun) {
            specs.add(new ToolSpec("run_candidate_migration",
                    "Replay the baseline migrations and seed, then run the candidate migration statement by "
                            + "statement in the disposable sandbox. Returns per-statement timing, errors, the "
                            + "locks each statement took, and the resulting table sizes. Call this first.",
                    ToolSpec.objectSchema(Map.of()),
                    args -> mapper.writeValueAsString(ensureCandidateRun())));
        }

        return specs;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private interface ConnFn<T> {
        T apply(Connection c) throws Exception;
    }

    private <T> T withConn(ConnFn<T> fn) throws Exception {
        if (sandbox == null) {
            throw new IllegalStateException("sandbox unavailable");
        }
        // Make sure the schema the read tools see is the post-migration one.
        ensureCandidateRun();
        try (Connection c = sandbox.open()) {
            return fn.apply(c);
        }
    }

    public Map<String, Object> evidenceBundle() {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("candidate_statements", SqlScript.split(input.migrationSql()).size());
        bundle.put("sandbox_run", ensureCandidateRun());
        bundle.put("drift", drift());
        return bundle;
    }

    @Override
    public void close() {
        // The SandboxSession is owned and closed by the orchestrator.
    }
}
