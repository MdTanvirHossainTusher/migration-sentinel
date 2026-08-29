-- flyway:executeInTransaction=false
CREATE INDEX CONCURRENTLY idx_audit_log_actor_id ON audit_log (actor_id);
