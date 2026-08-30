package com.migrationsentinel.repository;

import com.migrationsentinel.model.entity.OutboxEventEntity;
import com.migrationsentinel.model.enums.OutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    Optional<OutboxEventEntity> findByIdAndStatus(UUID id, OutboxStatus status);

    /**
     * Due PENDING rows, oldest first, locked so parallel relay pods each take a disjoint
     * slice. {@code SKIP LOCKED} is a Postgres feature; the kafka transport only ever runs
     * against Postgres, and the {@code local} transport never calls this.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT e FROM OutboxEventEntity e
            WHERE e.status = com.migrationsentinel.model.enums.OutboxStatus.PENDING
              AND e.nextAttemptAt <= :now
            ORDER BY e.nextAttemptAt ASC
            """)
    List<OutboxEventEntity> lockDueBatch(@Param("now") Instant now, Limit limit);

    long countByStatus(OutboxStatus status);
}
