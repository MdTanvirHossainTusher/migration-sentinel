package com.migrationsentinel.service.sandbox;

import com.migrationsentinel.payload.dto.TableStat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only introspection over a sandbox connection. These are the queries behind the
 * agent's read tools: row estimates from pg_class, real shape from information_schema,
 * indexes from pg_index, foreign keys from pg_constraint, and EXPLAIN plans.
 */
@Slf4j
@Component
public class SchemaIntrospector {

    public List<String> userTables(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    public long estimatedRows(Connection c, String table) throws SQLException {
        String sql = "SELECT reltuples::bigint FROM pg_class WHERE oid = to_regclass(?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(rs.getLong(1), 0) : -1;
            }
        }
    }

    public long exactRows(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + quote(table))) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public long tableSizeBytes(Connection c, String table) throws SQLException {
        String sql = "SELECT COALESCE(pg_total_relation_size(to_regclass(?)), 0)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public List<TableStat.ColumnInfo> columns(Connection c, String table) throws SQLException {
        List<TableStat.ColumnInfo> cols = new ArrayList<>();
        String sql = """
                SELECT column_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                ORDER BY ordinal_position
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(new TableStat.ColumnInfo(
                            rs.getString(1), rs.getString(2),
                            "YES".equalsIgnoreCase(rs.getString(3)), rs.getString(4)));
                }
            }
        }
        return cols;
    }

    public List<TableStat.IndexInfo> indexes(Connection c, String table) throws SQLException {
        List<TableStat.IndexInfo> out = new ArrayList<>();
        String sql = """
                SELECT i.relname AS index_name,
                       ix.indisunique,
                       ix.indisprimary,
                       (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                          FROM unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord)
                          JOIN pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = k.attnum) AS cols
                FROM pg_index ix
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_class t ON t.oid = ix.indrelid
                WHERE t.relname = ? AND t.relnamespace = 'public'::regnamespace
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cols = rs.getString(4);
                    out.add(new TableStat.IndexInfo(
                            rs.getString(1),
                            cols == null ? List.of() : List.of(cols.split(",")),
                            rs.getBoolean(2), rs.getBoolean(3)));
                }
            }
        }
        return out;
    }

    public List<TableStat.ForeignKeyInfo> foreignKeys(Connection c, String table,
                                                      List<TableStat.IndexInfo> indexes) throws SQLException {
        List<TableStat.ForeignKeyInfo> out = new ArrayList<>();
        String sql = """
                SELECT con.conname,
                       (SELECT string_agg(att.attname, ',' ORDER BY k.ord)
                          FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                          JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum) AS cols,
                       cl.relname AS referenced_table
                FROM pg_constraint con
                JOIN pg_class src ON src.oid = con.conrelid
                JOIN pg_class cl ON cl.oid = con.confrelid
                WHERE con.contype = 'f' AND src.relname = ? AND src.relnamespace = 'public'::regnamespace
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colsCsv = rs.getString(2);
                    List<String> cols = colsCsv == null ? List.of() : List.of(colsCsv.split(","));
                    boolean covered = !cols.isEmpty() && indexes.stream()
                            .anyMatch(ix -> !ix.columns().isEmpty()
                                    && ix.columns().get(0).equalsIgnoreCase(cols.get(0)));
                    out.add(new TableStat.ForeignKeyInfo(rs.getString(1), cols, rs.getString(3), covered));
                }
            }
        }
        return out;
    }

    public TableStat tableStat(Connection c, String table) throws SQLException {
        List<TableStat.IndexInfo> ix = indexes(c, table);
        long estimated = estimatedRows(c, table);
        Long exact = null;
        // Only take an exact count when the estimate is small or missing; on a genuinely
        // large seeded table the estimate (post-ANALYZE) is what we trust.
        if (estimated < 0 || estimated < 200_000) {
            exact = exactRows(c, table);
        }
        return new TableStat(
                table,
                estimated,
                exact,
                tableSizeBytes(c, table),
                columns(c, table),
                ix,
                foreignKeys(c, table, ix));
    }

    public Map<String, TableStat> snapshot(Connection c) throws SQLException {
        Map<String, TableStat> out = new LinkedHashMap<>();
        for (String t : userTables(c)) {
            out.put(t, tableStat(c, t));
        }
        return out;
    }

    public String explain(Connection c, String query) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN " + query)) {
            while (rs.next()) {
                sb.append(rs.getString(1)).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
