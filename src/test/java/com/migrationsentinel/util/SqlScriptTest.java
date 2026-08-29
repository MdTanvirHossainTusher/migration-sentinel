package com.migrationsentinel.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScriptTest {

    @Test
    void splitsOnTopLevelSemicolons() {
        List<String> statements = SqlScript.split("CREATE TABLE a (id int); ALTER TABLE a ADD COLUMN b int;");
        assertThat(statements).containsExactly(
                "CREATE TABLE a (id int)",
                "ALTER TABLE a ADD COLUMN b int");
    }

    @Test
    void ignoresSemicolonsInsideStringsAndDollarQuotes() {
        String script = "INSERT INTO t VALUES ('a;b'); "
                + "CREATE FUNCTION f() RETURNS void AS $$ BEGIN PERFORM 1; END; $$ LANGUAGE plpgsql;";
        List<String> statements = SqlScript.split(script);
        assertThat(statements).hasSize(2);
        assertThat(statements.get(1)).contains("LANGUAGE plpgsql");
    }

    @Test
    void keepsLineCommentsButDoesNotSplitOnThem() {
        List<String> statements = SqlScript.split("-- a comment; still a comment\nSELECT 1;");
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("SELECT 1");
    }
}
