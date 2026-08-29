CREATE TABLE orders (
    id bigserial PRIMARY KEY,
    placed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE shipments (
    id bigserial PRIMARY KEY,
    carrier varchar(32) NOT NULL,
    shipped_at timestamptz
);
