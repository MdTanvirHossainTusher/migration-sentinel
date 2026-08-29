CREATE TABLE products (
    id bigserial PRIMARY KEY,
    sku varchar(40) NOT NULL,
    price_cents bigint NOT NULL
);
