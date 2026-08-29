package com.migrationsentinel.payload.response;

import java.util.List;

public record EvaluationDetailResponse(
        EvaluationRunResponse run,
        List<EvaluationCaseResultResponse> cases
) {
}
