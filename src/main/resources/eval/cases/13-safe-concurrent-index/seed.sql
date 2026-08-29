INSERT INTO audit_log (actor_id, action) SELECT g, 'LOGIN' FROM generate_series(1, 2000) g;
UPDATE pg_class SET reltuples = 40000000, relpages = 900000 WHERE relname = 'audit_log';
