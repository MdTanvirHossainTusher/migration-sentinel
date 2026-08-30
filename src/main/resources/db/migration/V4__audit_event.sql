-- Durable audit trail. Every consequential thing the service does — a review submitted, a
-- run completed, a rewrite written to disk, an artifact confirmed — lands here in the same
-- transaction as the change it describes. Payloads are passed through the secret masker
-- before they are written, so an API key handed in on a request never reaches this table.
-- Under the kafka transport the same event is also relayed on migration-sentinel.audit.

CREATE TABLE audit_event (
    id             uuid PRIMARY KEY,
    created_at     timestamp with time zone NOT NULL,
    updated_at     timestamp with time zone NOT NULL,
    event_type     varchar(64)  NOT NULL,
    aggregate_type varchar(64)  NOT NULL,
    aggregate_id   varchar(64),
    actor          varchar(128),
    summary        varchar(500),
    payload        text,
    correlation_id varchar(64)
);
CREATE INDEX ix_audit_event_created_at ON audit_event (created_at);
CREATE INDEX ix_audit_event_aggregate  ON audit_event (aggregate_type, aggregate_id);
CREATE INDEX ix_audit_event_type       ON audit_event (event_type);
