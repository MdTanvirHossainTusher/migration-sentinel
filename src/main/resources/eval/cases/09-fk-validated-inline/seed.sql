INSERT INTO accounts DEFAULT VALUES;
INSERT INTO postings (account_id, amount_cents) SELECT 1, g FROM generate_series(1, 2000) g;
UPDATE pg_class SET reltuples = 30000000, relpages = 700000 WHERE relname = 'postings';
