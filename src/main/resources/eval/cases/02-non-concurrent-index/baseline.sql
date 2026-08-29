CREATE TABLE orders (
    id           bigserial PRIMARY KEY,
    customer_id  bigint NOT NULL,
    status       varchar(32) NOT NULL DEFAULT 'PENDING',
    total_cents  bigint NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
