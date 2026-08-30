package com.migrationsentinel.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.migrationsentinel.messaging.JobMessages;
import com.migrationsentinel.messaging.outbox.OutboxRecorder;
import com.migrationsentinel.model.entity.AuditEventEntity;
import com.migrationsentinel.repository.AuditEventRepository;
import com.migrationsentinel.util.SecretMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Records an audit event in the same transaction as the change it describes, so the two
 * commit or roll back together. Under the {@code kafka} transport the event is also handed
 * to the outbox for relay on {@code migration-sentinel.audit}. Every payload and summary is
 * run through {@link SecretMasker} first — a per-request API key never lands here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<OutboxRecorder> outboxRecorder;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String eventType, String aggregateType, String aggregateId,
                       String actor, String summary, Map<String, Object> payload) {
        String json = serialize(payload);

        AuditEventEntity row = new AuditEventEntity();
        row.setEventType(eventType);
        row.setAggregateType(aggregateType);
        row.setAggregateId(aggregateId);
        row.setActor(actor);
        row.setSummary(truncate(SecretMasker.mask(summary), 500));
        row.setPayload(json);
        repository.save(row);

        outboxRecorder.ifAvailable(r -> {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", row.getId().toString());
            event.put("eventType", eventType);
            event.put("aggregateType", aggregateType);
            event.put("aggregateId", aggregateId);
            event.put("actor", actor);
            event.put("summary", summary);
            event.put("payload", payload == null ? Map.of() : payload);
            r.record(JobMessages.TOPIC_AUDIT, aggregateType, aggregateId, eventType, event);
        });
    }

    private String serialize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return SecretMasker.mask(objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            log.warn("audit payload serialization failed: {}", ex.getMessage());
            return "{\"_serialization_error\":true}";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
