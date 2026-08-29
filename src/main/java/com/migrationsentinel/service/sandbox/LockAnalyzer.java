package com.migrationsentinel.service.sandbox;

import com.migrationsentinel.payload.dto.LockObservation;
import com.migrationsentinel.service.rules.ParsedStatement;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Two sources of lock evidence:
 *  1. Static inference — the lock level a given DDL kind is documented to take.
 *  2. Live observation — while a candidate statement runs on connection A, a poll on
 *     connection B reads pg_locks for A's backend pid. On the tiny sandbox tables most
 *     DDL finishes before a poll lands, so (1) is the dependable signal and (2) is a
 *     bonus when a seeded table makes the statement slow enough to catch.
 */
@Component
public class LockAnalyzer {

    public String inferStrongestLock(ParsedStatement st) {
        return switch (st.kind()) {
            case DROP_TABLE, DROP_COLUMN, SET_NOT_NULL, DROP_NOT_NULL, ALTER_COLUMN_TYPE,
                 ADD_COLUMN, RENAME_COLUMN, RENAME_TABLE, TRUNCATE, ALTER_TYPE_ADD_VALUE,
                 ADD_CHECK_CONSTRAINT, ADD_UNIQUE_CONSTRAINT -> "ACCESS EXCLUSIVE";
            case ADD_FOREIGN_KEY -> st.notValid() ? "SHARE ROW EXCLUSIVE" : "SHARE ROW EXCLUSIVE + ACCESS EXCLUSIVE (validate)";
            case CREATE_INDEX -> st.concurrently() ? "SHARE UPDATE EXCLUSIVE" : "SHARE";
            case VALIDATE_CONSTRAINT -> "SHARE UPDATE EXCLUSIVE";
            case DROP_INDEX -> st.concurrently() ? "SHARE UPDATE EXCLUSIVE" : "ACCESS EXCLUSIVE";
            case CREATE_TABLE -> "none (new object)";
            default -> "unknown";
        };
    }

    /** Poll pg_locks for {@code backendPid} once. Returns any table-level locks it currently holds. */
    public List<LockObservation> pollLocks(Connection probe, int backendPid, String statement) {
        List<LockObservation> out = new ArrayList<>();
        String sql = """
                SELECT COALESCE(c.relname, l.locktype) AS rel, l.mode
                FROM pg_locks l
                LEFT JOIN pg_class c ON c.oid = l.relation
                WHERE l.pid = ? AND l.granted AND l.locktype = 'relation'
                  AND (c.relnamespace IS NULL OR c.relnamespace = 'public'::regnamespace)
                """;
        try (PreparedStatement ps = probe.prepareStatement(sql)) {
            ps.setInt(1, backendPid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LockObservation(rs.getString(1), rs.getString(2), statement, true));
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return out;
    }
}
