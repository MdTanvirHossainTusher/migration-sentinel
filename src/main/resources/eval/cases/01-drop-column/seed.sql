INSERT INTO customers (email, legacy_ref)
SELECT 'user' || g || '@example.com', 'L-' || g
FROM generate_series(1, 500) g;
