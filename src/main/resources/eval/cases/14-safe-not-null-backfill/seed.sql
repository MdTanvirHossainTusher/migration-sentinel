INSERT INTO subscriptions (plan, renews_at) SELECT 'PRO', now() FROM generate_series(1, 3000);
UPDATE pg_class SET reltuples = 7000000, relpages = 140000 WHERE relname = 'subscriptions';
