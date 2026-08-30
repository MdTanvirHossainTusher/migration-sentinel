package com.migrationsentinel.model.enums;

public enum ArtifactKind {
    /** The rendered review report, stored server-side when a review completes. */
    REVIEW_REPORT,
    /** A file uploaded by a user (e.g. a migration folder archive) via a presigned URL. */
    USER_UPLOAD
}
