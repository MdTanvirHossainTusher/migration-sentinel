INSERT INTO ledger (account_id, money_cents) SELECT g, g*10 FROM generate_series(1, 2000) g;
UPDATE pg_class SET reltuples = 20000000, relpages = 500000 WHERE relname = 'ledger';
