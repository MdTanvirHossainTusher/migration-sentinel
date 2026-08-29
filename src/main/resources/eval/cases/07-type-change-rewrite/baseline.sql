CREATE TABLE ledger (
    id bigserial PRIMARY KEY,
    account_id bigint NOT NULL,
    money_cents integer NOT NULL
);
