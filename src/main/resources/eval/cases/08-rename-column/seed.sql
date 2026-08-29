INSERT INTO profiles (full_name) SELECT 'Person ' || g FROM generate_series(1, 300) g;
