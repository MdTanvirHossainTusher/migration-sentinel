ALTER TABLE documents DROP COLUMN legacy_blob_ref;
CREATE INDEX idx_documents_created_at ON documents (created_at);
ALTER TABLE documents ADD COLUMN tenant_id bigint;
ALTER TABLE documents ADD CONSTRAINT fk_documents_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) NOT VALID;
ALTER TABLE documents VALIDATE CONSTRAINT fk_documents_tenant;
