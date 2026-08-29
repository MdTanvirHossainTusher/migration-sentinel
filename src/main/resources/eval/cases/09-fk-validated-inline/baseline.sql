CREATE TABLE accounts (id bigserial PRIMARY KEY);
CREATE TABLE postings (
    id bigserial PRIMARY KEY,
    account_id bigint NOT NULL,
    amount_cents bigint NOT NULL
);
CREATE INDEX ix_postings_account_id ON postings (account_id);
