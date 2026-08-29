INSERT INTO orders (placed_at) SELECT now() FROM generate_series(1, 1000);
INSERT INTO shipments (carrier) SELECT 'UPS' FROM generate_series(1, 1000);
UPDATE pg_class SET reltuples = 4000000, relpages = 90000 WHERE relname = 'shipments';
