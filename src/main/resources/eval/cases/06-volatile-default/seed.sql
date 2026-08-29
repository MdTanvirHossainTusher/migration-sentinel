INSERT INTO events (kind) SELECT 'click' FROM generate_series(1, 2000);
UPDATE pg_class SET reltuples = 12000000, relpages = 400000 WHERE relname = 'events';
