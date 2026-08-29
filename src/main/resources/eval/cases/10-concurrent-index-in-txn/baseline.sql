CREATE TABLE sessions (
    id uuid PRIMARY KEY,
    user_id bigint NOT NULL,
    last_seen timestamptz NOT NULL DEFAULT now()
);
