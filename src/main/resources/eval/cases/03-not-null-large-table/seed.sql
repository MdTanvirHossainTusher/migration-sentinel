INSERT INTO invoices (order_id, tax_region)
SELECT g, 'EU' FROM generate_series(1, 3000) g;
UPDATE pg_class SET reltuples = 5000000, relpages = 190000 WHERE relname = 'invoices';
