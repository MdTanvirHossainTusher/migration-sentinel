INSERT INTO orders (customer_id, status, total_cents)
SELECT (g % 1000) + 1, 'PENDING', (g * 100)
FROM generate_series(1, 2000) g;
-- Simulate production scale without inserting 8M rows.
UPDATE pg_class SET reltuples = 8000000, relpages = 300000 WHERE relname = 'orders';
