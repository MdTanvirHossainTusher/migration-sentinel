CREATE TABLE invoices (
    id           bigserial PRIMARY KEY,
    order_id     bigint NOT NULL,
    tax_region   varchar(8),
    issued_at    timestamptz NOT NULL DEFAULT now()
);
