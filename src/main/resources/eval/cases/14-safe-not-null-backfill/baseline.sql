CREATE TABLE subscriptions (
    id bigserial PRIMARY KEY,
    plan varchar(32) NOT NULL,
    renews_at timestamptz
);
