-- Transactional outbox. When sentinel.messaging.transport=kafka, a job submission writes
-- one row here in the SAME transaction as the review_job / evaluation_run insert, so the
-- job and its "please run me" message commit or roll back together. A relay ships PENDING
-- rows to Kafka (an AFTER_COMMIT immediate publish for latency, plus a scheduled sweep that
-- recovers anything the broker missed), then deletes them. The `local` transport never
-- touches this table.

CREATE TABLE outbox_event (
    id              uuid PRIMARY KEY,
    created_at      timestamp with time zone NOT NULL,
    updated_at      timestamp with time zone NOT NULL,
    aggregate_type  varchar(64)  NOT NULL,
    aggregate_id    varchar(64),
    topic           varchar(128) NOT NULL,
    event_type      varchar(64)  NOT NULL,
    payload         text         NOT NULL,
    status          varchar(16)  NOT NULL DEFAULT 'PENDING',
    retry_count     integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamp with time zone NOT NULL,
    published_at    timestamp with time zone,
    last_error      text
);

-- Relay scan: PENDING rows whose next attempt is due, oldest first.
CREATE INDEX ix_outbox_event_due ON outbox_event (status, next_attempt_at);
