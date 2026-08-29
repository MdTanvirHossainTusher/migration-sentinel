package com.migrationsentinel.service.sandbox;

import com.migrationsentinel.config.properties.SandboxProperties;
import com.migrationsentinel.payload.dto.SandboxRunResult;
import com.migrationsentinel.service.rules.DdlParser;
import com.migrationsentinel.support.SandboxTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void guardRejectsNonSandboxUrl() {
        assertThat(session.jdbcUrl()).startsWith("jdbc:postgresql://");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> session.assertIsSandbox("jdbc:postgresql://prod-db:5432/app"));
    }
}
