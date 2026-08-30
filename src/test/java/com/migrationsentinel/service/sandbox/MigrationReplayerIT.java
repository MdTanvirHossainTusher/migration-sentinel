package com.migrationsentinel.service.sandbox;

import com.migrationsentinel.config.properties.SandboxProperties;
import com.migrationsentinel.payload.dto.MigrationFile;
import com.migrationsentinel.payload.dto.SandboxRunResult;
import com.migrationsentinel.service.rules.DdlParser;
import com.migrationsentinel.support.SandboxTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SandboxTest
class MigrationReplayerIT {

    private SandboxManager manager;
    private SandboxSession session;
    private MigrationReplayer replayer;
    private SchemaIntrospector introspector;

    @BeforeEach
    void setUp() {
        SandboxProperties props = new SandboxProperties();
        manager = new SandboxManager(props);
        introspector = new SchemaIntrospector();
        replayer = new MigrationReplayer(props, introspector, new LockAnalyzer(), new DdlParser());
        session = manager.start();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void appliesBaselineSeedAndCandidateAndReportsRowEstimate() {
        String baseline = "CREATE TABLE orders (id bigserial PRIMARY KEY, status varchar(16) NOT NULL DEFAULT 'NEW');";
        String seed = "INSERT INTO orders (status) SELECT 'NEW' FROM generate_series(1, 100);"
                + " UPDATE pg_class SET reltuples = 5000000 WHERE relname = 'orders';";
        String candidate = "CREATE INDEX idx_orders_status ON orders (status);";

        SandboxRunResult result = replayer.replay(session, baseline, seed, candidate);

        assertThat(result.baselineApplied()).isTrue();
        assertThat(result.candidateApplied()).isTrue();
        assertThat(result.statements()).hasSize(1);
        assertThat(result.tableStatsAfter())
                .anySatisfy(t -> assertThat(t.table()).isEqualTo("orders"));
    }

    @Test
    void replaysAWholeMigrationHistoryInOrderNotStringOrder() {
        // V10 must land after V2: replayed as strings, V10's ALTER would hit a column that
        // V2 has not added yet and the whole baseline would fail.
        List<MigrationFile> history = List.of(
                new MigrationFile("V1__create.sql", "CREATE TABLE orders (id bigserial PRIMARY KEY);"),
                new MigrationFile("V2__add_status.sql", "ALTER TABLE orders ADD COLUMN status varchar(16);"),
                new MigrationFile("V10__index_status.sql", "CREATE INDEX ix_orders_status ON orders (status);"));

        SandboxRunResult result = replayer.replay(session, history, null, null,
                "ALTER TABLE orders ADD COLUMN tenant_id bigint;", null);

        assertThat(result.baselineApplied()).isTrue();
        assertThat(result.candidateApplied()).isTrue();
        assertThat(result.baselineOrNone().filesApplied()).isEqualTo(3);
        assertThat(result.baselineOrNone().statementsApplied()).isEqualTo(3);
        assertThat(result.baselineOrNone().failed()).isFalse();
        assertThat(result.schemaObserved()).isTrue();
    }

    @Test
    void namesTheMigrationThatStoppedTheReplay() {
        List<MigrationFile> history = List.of(
                new MigrationFile("V1__create.sql", "CREATE TABLE orders (id bigserial PRIMARY KEY);"),
                new MigrationFile("V2__needs_extension.sql", "CREATE EXTENSION \"no_such_extension\";"),
                new MigrationFile("V3__later.sql", "ALTER TABLE orders ADD COLUMN status varchar(16);"));

        SandboxRunResult result = replayer.replay(session, history, null, null,
                "ALTER TABLE orders ALTER COLUMN status SET NOT NULL;", null);

        SandboxRunResult.BaselineReplay baseline = result.baselineOrNone();
        assertThat(baseline.failed()).isTrue();
        assertThat(baseline.failedFile()).isEqualTo("V2__needs_extension.sql");
        assertThat(baseline.filesApplied()).isEqualTo(1);
        assertThat(baseline.filesTotal()).isEqualTo(3);
        assertThat(baseline.describeFailure()).contains("V2__needs_extension.sql", "1 of 3 files applied");

        // A half-built schema must not read as a measured one, or the rules will cite
        // pg_index for a lookup that never happened.
        assertThat(result.schemaObserved()).isFalse();
        assertThat(result.baselineApplied()).isFalse();
        assertThat(result.tableStatsAfter()).isEmpty();
    }

    @Test
    void guardRejectsNonSandboxUrl() {
        assertThat(session.jdbcUrl()).startsWith("jdbc:postgresql://");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> session.assertIsSandbox("jdbc:postgresql://prod-db:5432/app"));
    }
}
