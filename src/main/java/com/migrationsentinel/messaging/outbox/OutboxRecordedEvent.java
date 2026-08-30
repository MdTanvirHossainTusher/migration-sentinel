package com.migrationsentinel.messaging.outbox;

import java.util.UUID;

/** Announces that an outbox row is committed-pending, so the relay can ship it immediately. */
public record OutboxRecordedEvent(UUID outboxId) {
}
