package com.migrationsentinel.service;

import com.migrationsentinel.payload.dto.MigrationFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationHistoryTest {

    private MigrationFile f(String name, String sql) {
        return new MigrationFile(name, sql);
    }

    private List<String> names(List<MigrationFile> files) {
        return files.stream().map(MigrationFile::filename).toList();
    }

    @Test
    void ordersByVersionNumberNotStringOrder() {
        List<MigrationFile> ordered = MigrationHistory.ordered(List.of(
                f("V10__ten.sql", "SELECT 10;"),
                f("V2__two.sql", "SELECT 2;"),
                f("V1__one.sql", "SELECT 1;"),
                f("V100__hundred.sql", "SELECT 100;")));

        assertThat(names(ordered))
                .containsExactly("V1__one.sql", "V2__two.sql", "V10__ten.sql", "V100__hundred.sql");
    }

    @Test
    void ordersDottedAndUnderscoredMinorVersions() {
        List<MigrationFile> ordered = MigrationHistory.ordered(List.of(
                f("V1_10__b.sql", "SELECT 1;"),
                f("V1.2__a.sql", "SELECT 2;"),
                f("V1__base.sql", "SELECT 3;")));

        assertThat(names(ordered)).containsExactly("V1__base.sql", "V1.2__a.sql", "V1_10__b.sql");
    }

    @Test
    void putsRepeatableMigrationsAfterVersionedOnes() {
        List<MigrationFile> ordered = MigrationHistory.ordered(List.of(
                f("R__views.sql", "SELECT 1;"),
                f("V3__three.sql", "SELECT 3;")));

        assertThat(names(ordered)).containsExactly("V3__three.sql", "R__views.sql");
    }

    @Test
    void dropsUndoMigrationsAndEmptyFiles() {
        List<MigrationFile> ordered = MigrationHistory.ordered(List.of(
                f("V1__one.sql", "SELECT 1;"),
                f("U1__undo_one.sql", "DROP TABLE one;"),
                f("V2__blank.sql", "   ")));

        assertThat(names(ordered)).containsExactly("V1__one.sql");
    }

    @Test
    void ignoresDirectoryPrefixesOnFilenames() {
        List<MigrationFile> ordered = MigrationHistory.ordered(List.of(
                f("src/main/resources/db/migration/V10__ten.sql", "SELECT 10;"),
                f("src/main/resources/db/migration/V9__nine.sql", "SELECT 9;")));

        assertThat(ordered.get(0).filename()).endsWith("V9__nine.sql");
        assertThat(ordered.get(1).filename()).endsWith("V10__ten.sql");
    }

    @Test
    void concatAndSplitRoundTripPreservingFilenames() {
        List<MigrationFile> history = MigrationHistory.ordered(List.of(
                f("V1__one.sql", "CREATE TABLE a (id int);"),
                f("V2__two.sql", "CREATE TABLE b (id int)")));

        List<MigrationFile> back = MigrationHistory.split(MigrationHistory.concat(history));

        assertThat(names(back)).containsExactly("V1__one.sql", "V2__two.sql");
        assertThat(back.get(0).sql()).contains("CREATE TABLE a");
        assertThat(back.get(1).sql()).contains("CREATE TABLE b");
    }

    @Test
    void splitTreatsAMarkerlessScriptAsOneFile() {
        List<MigrationFile> back = MigrationHistory.split("CREATE TABLE a (id int);");

        assertThat(back).hasSize(1);
        assertThat(back.get(0).filename()).isEqualTo("baseline.sql");
    }

    @Test
    void concatTerminatesAFileThatOmitsItsFinalSemicolon() {
        String script = MigrationHistory.concat(List.of(
                f("V1__one.sql", "CREATE TABLE a (id int)"),
                f("V2__two.sql", "CREATE TABLE b (id int)")));

        assertThat(script).contains("CREATE TABLE a (id int)\n;");
    }

    @Test
    void describeNamesTheVersionRange() {
        assertThat(MigrationHistory.describe(MigrationHistory.ordered(List.of(
                f("V1__a.sql", "SELECT 1;"),
                f("V440__b.sql", "SELECT 2;")))))
                .isEqualTo("2 migrations, V1 → V440");
    }
}
