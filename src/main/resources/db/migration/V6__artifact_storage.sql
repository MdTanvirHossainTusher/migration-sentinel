-- Downloadable artifacts kept in object storage (RustFS / any S3 API). A row is written
-- PENDING when a presigned upload URL is handed out, then CONFIRMED once the object is
-- verified to exist and be within the size limit. The rendered review report is stored the
-- same way, server-side, so report.md is a real file the user can download rather than a
-- blob of text in a JSON response.

CREATE TABLE artifact (
    id            uuid PRIMARY KEY,
    created_at    timestamp with time zone NOT NULL,
    updated_at    timestamp with time zone NOT NULL,
    kind          varchar(32)  NOT NULL,
    status        varchar(16)  NOT NULL DEFAULT 'PENDING',
    object_key    varchar(512) NOT NULL,
    filename      varchar(255) NOT NULL,
    content_type  varchar(128),
    size_bytes    bigint,
    review_job_id uuid,
    created_by    varchar(128)
);
CREATE INDEX ix_artifact_review_job_id ON artifact (review_job_id);
CREATE INDEX ix_artifact_status        ON artifact (status);

ALTER TABLE review_job ADD COLUMN report_artifact_id uuid;
