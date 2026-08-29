package com.migrationsentinel.service.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdlParserTest {

    private final DdlParser parser = new DdlParser();

    @Test
    void classifiesDropColumn() {
        ParsedStatement st = parser.parse("ALTER TABLE customers DROP COLUMN legacy_ref;").get(0);
        assertThat(st.kind()).isEqualTo(ParsedStatement.Kind.DROP_COLUMN);
        assertThat(st.table()).isEqualTo("customers");
        assertThat(st.columns()).containsExactly("legacy_ref");
    }

    @Test
    void classifiesSetNotNull() {
        ParsedStatement st = parser.parse("ALTER TABLE invoices ALTER COLUMN tax_region SET NOT NULL;").get(0);
        assertThat(st.kind()).isEqualTo(ParsedStatement.Kind.SET_NOT_NULL);
        assertThat(st.columns()).containsExactly("tax_region");
    }

    @Test
    void detectsConcurrentIndex() {
        ParsedStatement st = parser.parse("CREATE INDEX CONCURRENTLY ix_a ON sessions (user_id);").get(0);
        assertThat(st.kind()).isEqualTo(ParsedStatement.Kind.CREATE_INDEX);
        assertThat(st.concurrently()).isTrue();
        assertThat(st.table()).isEqualTo("sessions");
        assertThat(st.columns()).containsExactly("user_id");
    }

    @Test
    void detectsVolatileDefaultOnAddColumn() {
        ParsedStatement st = parser.parse(
                "ALTER TABLE events ADD COLUMN trace_id uuid NOT NULL DEFAULT gen_random_uuid();").get(0);
        assertThat(st.kind()).isEqualTo(ParsedStatement.Kind.ADD_COLUMN);
        assertThat(st.defaultExpr()).contains("gen_random_uuid");
    }

    @Test
    void classifiesForeignKeyAddWithNotValid() {
        List<ParsedStatement> statements = parser.parse(
                "ALTER TABLE shipments ADD CONSTRAINT fk FOREIGN KEY (order_id) REFERENCES orders (id) NOT VALID;");
        ParsedStatement st = statements.get(0);
        assertThat(st.kind()).isEqualTo(ParsedStatement.Kind.ADD_FOREIGN_KEY);
        assertThat(st.columns()).containsExactly("order_id");
        assertThat(st.referencedTable()).isEqualTo("orders");
        assertThat(st.notValid()).isTrue();
    }
}
