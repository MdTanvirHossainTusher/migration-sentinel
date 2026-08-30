package com.migrationsentinel.messaging.outbox;

import com.migrationsentinel.model.entity.OutboxEventEntity;
import com.migrationsentinel.model.enums.OutboxStatus;
import com.migrationsentinel.repository.OutboxEventRepository;
import com.migrationsentinel.util.SecretMasker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes an event into the outbox as part of the caller's transaction, then announces it so
 * the relay can ship it the moment that transaction commits. Present only under the
 * {@code kafka} transport.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sentinel.messaging", name = "transport", havingValue = "kafka")
public class OutboxRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    @Value("${sentinel.messaging.outbox.immediate-grace-seconds:10}")
    private long immediateGraceSeconds;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String topic, String aggregateType, String aggregateId,
                       String eventType, Map<String, Object> payload) {
        String json;
        try {
            json = SecretMasker.mask(objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, ex);
        }

        OutboxEventEntity row = new OutboxEventEntity();
        row.setAggregateType(aggregateType);
        row.setAggregateId(aggregateId);
        row.setTopic(topic);
        row.setEventType(eventType);
        row.setPayload(json);
        row.setStatus(OutboxStatus.PENDING);
        row.setNextAttemptAt(Instant.now().plusSeconds(immediateGraceSeconds));
        row = repository.save(row);

        UUID id = row.getId();
        events.publishEvent(new OutboxRecordedEvent(id));
    }
}
