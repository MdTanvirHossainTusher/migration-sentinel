CREATE TABLE audit_log (
    id bigserial PRIMARY KEY,
    actor_id bigint NOT NULL,
    action varchar(64) NOT NULL,
    at timestamptz NOT NULL DEFAULT now()
);
