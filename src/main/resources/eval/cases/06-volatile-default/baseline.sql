CREATE TABLE events (
    id bigserial PRIMARY KEY,
    kind varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
