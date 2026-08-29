INSERT INTO sessions (id, user_id) SELECT gen_random_uuid(), g FROM generate_series(1, 1000) g;
UPDATE pg_class SET reltuples = 6000000, relpages = 120000 WHERE relname = 'sessions';
