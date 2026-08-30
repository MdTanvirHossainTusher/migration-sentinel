package com.migrationsentinel.payload.response;

import java.util.List;

public record ReviewReportResponse(
        ReviewResponse review,
        String reportMarkdown,
        /** Presigned URL to download report.md, when object storage is enabled; else null. */
        String reportDownloadUrl,
        List<FindingResponse> findings,
        List<ToolCallResponse> trajectory
) {
}
