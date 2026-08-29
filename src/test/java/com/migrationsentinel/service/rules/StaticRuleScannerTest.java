package com.migrationsentinel.service.rules;

import com.migrationsentinel.model.enums.RuleCode;
import com.migrationsentinel.model.enums.Severity;
import com.migrationsentinel.payload.dto.ProposedFinding;
import com.migrationsentinel.payload.dto.SchemaDriftReport;
import com.migrationsentinel.payload.dto.TableStat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticRuleScannerTest {

    private final StaticRuleScanner scanner = new StaticRuleScanner(new DdlParser());
    private final long threshold = 1_000_000L;

    private SchemaFacts factsWith(TableStat... tables) {
        return new SchemaFacts(List.of(tables), threshold, true);
    }

    private TableStat table(String name, long rows, TableStat.IndexInfo... indexes) {
        return new TableStat(name, rows, rows, 0, List.of(), List.of(indexes), List.of());
    }

    @Test
    void flagsSetNotNullHighOnLargeTable() {
        SchemaFacts facts = factsWith(table("invoices", 5_000_000));
        List<ProposedFinding> findings = scanner.scan(
                "ALTER TABLE invoices ALTER COLUMN tax_region SET NOT NULL;", facts, null);
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.ruleCode()).isEqualTo(RuleCode.NOT_NULL_WITHOUT_SAFE_BACKFILL);
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
        });
    }

    @Test
    void staysQuietOnSetNotNullForEmptyTable() {
        SchemaFacts facts = factsWith(table("feature_flags", 0));
        List<ProposedFinding> findings = scanner.scan(
                "ALTER TABLE feature_flags ALTER COLUMN rollout_pct SET NOT NULL;", facts, null);
        assertThat(findings).noneMatch(f -> f.ruleCode() == RuleCode.NOT_NULL_WITHOUT_SAFE_BACKFILL);
    }

    @Test
    void flagsNonConcurrentIndex() {
        SchemaFacts facts = factsWith(table("orders", 8_000_000));
        List<ProposedFinding> findings = scanner.scan("CREATE INDEX ix ON orders (status);", facts, null);
        assertThat(findings).anyMatch(f -> f.ruleCode() == RuleCode.NON_CONCURRENT_INDEX);
    }

    @Test
    void flagsUnindexedForeignKey() {
        SchemaFacts facts = factsWith(table("shipments", 4_000_000,
                new TableStat.IndexInfo("pk", List.of("id"), true, true)));
        List<ProposedFinding> findings = scanner.scan(
                "ALTER TABLE shipments ADD CONSTRAINT fk FOREIGN KEY (order_id) REFERENCES orders (id) NOT VALID;",
                facts, null);
        assertThat(findings).anyMatch(f -> f.ruleCode() == RuleCode.UNINDEXED_FOREIGN_KEY
                && "shipments.order_id".equals(f.targetObject()));
    }

    @Test
    void staysQuietWhenForeignKeyColumnIsIndexed() {
        SchemaFacts facts = factsWith(table("postings", 3_000_000,
                new TableStat.IndexInfo("ix_postings_account_id", List.of("account_id"), false, false)));
        List<ProposedFinding> findings = scanner.scan(
                "ALTER TABLE postings ADD CONSTRAINT fk FOREIGN KEY (account_id) REFERENCES accounts (id) NOT VALID;",
                facts, null);
        assertThat(findings).noneMatch(f -> f.ruleCode() == RuleCode.UNINDEXED_FOREIGN_KEY);
    }

    @Test
    void reportsEntityDrift() {
        SchemaDriftReport drift = new SchemaDriftReport(true, false,
                List.of(new SchemaDriftReport.DriftItem("User", "display_name is nullable=false but column is NULLABLE")),
                "1 mismatch");
        List<ProposedFinding> findings = scanner.scan(
                "ALTER TABLE users ADD COLUMN display_name varchar(120);", SchemaFacts.empty(threshold), drift);
        assertThat(findings).anyMatch(f -> f.ruleCode() == RuleCode.ENTITY_SCHEMA_DRIFT);
    }

    @Test
    void cleanMigrationProducesNoFindings() {
        SchemaFacts facts = factsWith(table("products", 9_000_000));
        List<ProposedFinding> findings = scanner.scan(
                "ALTER TABLE products ADD COLUMN discontinued_at timestamptz;", facts, null);
        assertThat(findings).isEmpty();
    }
}
