CREATE TABLE customers (
    id          bigserial PRIMARY KEY,
    email       varchar(255) NOT NULL,
    legacy_ref  varchar(64),
    created_at  timestamptz NOT NULL DEFAULT now()
);
