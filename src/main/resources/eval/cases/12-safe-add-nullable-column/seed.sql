INSERT INTO products (sku, price_cents) SELECT 'SKU-' || g, g*100 FROM generate_series(1, 2000) g;
UPDATE pg_class SET reltuples = 9000000, relpages = 150000 WHERE relname = 'products';
