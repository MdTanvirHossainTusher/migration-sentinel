CREATE TABLE tenants (id bigserial PRIMARY KEY);
CREATE TABLE documents (
    id bigserial PRIMARY KEY,
    title varchar(200) NOT NULL,
    legacy_blob_ref varchar(128),
    body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
