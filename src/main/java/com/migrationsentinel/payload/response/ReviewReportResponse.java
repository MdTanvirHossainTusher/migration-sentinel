package com.migrationsentinel.payload.response;

import java.util.List;

public record ReviewReportResponse(
        ReviewResponse review,
        String reportMarkdown,
        List<FindingResponse> findings,
        List<ToolCallResponse> trajectory
) {
}
