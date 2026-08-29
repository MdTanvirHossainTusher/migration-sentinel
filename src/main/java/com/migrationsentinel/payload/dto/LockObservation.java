package com.migrationsentinel.payload.dto;

/** A lock the candidate migration took, observed in pg_locks or inferred from the DDL. */
public record LockObservation(
        String relation,
        String lockMode,
        String statement,
        boolean observedInCatalog
) {
}
