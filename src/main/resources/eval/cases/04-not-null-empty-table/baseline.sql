CREATE TABLE feature_flags (
    id          bigserial PRIMARY KEY,
    key         varchar(64) NOT NULL,
    rollout_pct integer
);
