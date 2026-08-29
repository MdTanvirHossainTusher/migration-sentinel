INSERT INTO tenants DEFAULT VALUES;
INSERT INTO documents (title, body) SELECT 'Doc ' || g, 'x' FROM generate_series(1, 3000) g;
UPDATE pg_class SET reltuples = 11000000, relpages = 600000 WHERE relname = 'documents';
