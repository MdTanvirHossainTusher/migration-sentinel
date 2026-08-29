ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_renews_at_nn
    CHECK (renews_at IS NOT NULL) NOT VALID;
ALTER TABLE subscriptions VALIDATE CONSTRAINT subscriptions_renews_at_nn;
ALTER TABLE subscriptions ALTER COLUMN renews_at SET NOT NULL;
