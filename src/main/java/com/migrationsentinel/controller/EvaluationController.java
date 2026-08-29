package com.migrationsentinel.controller;

import com.migrationsentinel.payload.common.ApiResponse;
import com.migrationsentinel.payload.common.PageResult;
import com.migrationsentinel.payload.common.ResponseBuilder;
import com.migrationsentinel.payload.request.RunEvaluationRequest;
import com.migrationsentinel.payload.response.EvaluationDetailResponse;
import com.migrationsentinel.payload.response.EvaluationRunResponse;
import com.migrationsentinel.service.eval.EvaluationCase;
import com.migrationsentinel.service.eval.EvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.migrationsentinel.constant.code.SuccessCodes.EVALUATION_ACCEPTED;

@RestController
@RequestMapping("/api/v1/evaluations")
@RequiredArgsConstructor
@Tag(name = "Evaluation", description = "Run the migration-safety corpus through a pipeline mode and score it")
public class EvaluationController {

    private final EvaluationService evaluationService;

    @PostMapping
    @Operation(summary = "Start an evaluation run (asynchronous)")
    public ResponseEntity<ApiResponse<EvaluationRunResponse>> run(@Valid @RequestBody RunEvaluationRequest request) {
        return ResponseBuilder.accepted(evaluationService.submit(request), EVALUATION_ACCEPTED,
                "Evaluation queued. Poll GET /api/v1/evaluations/{id} for progress and metrics.");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Evaluation run: aggregate metrics plus per-case scores")
    public ResponseEntity<ApiResponse<EvaluationDetailResponse>> get(@PathVariable UUID id) {
        return ResponseBuilder.ok(evaluationService.get(id));
    }

    @GetMapping
    @Operation(summary = "List evaluation runs")
    public ResponseEntity<ApiResponse<List<EvaluationRunResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<EvaluationRunResponse> result = evaluationService.list(page, size);
        return ResponseBuilder.ok(result.data(), result.pagination());
    }

    @GetMapping("/cases")
    @Operation(summary = "The evaluation corpus: id, title, expected findings, whether it is a hard case")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> cases() {
        List<Map<String, Object>> cases = evaluationService.cases().stream()
                .map(this::summarize)
                .toList();
        return ResponseBuilder.ok(cases);
    }

    private Map<String, Object> summarize(EvaluationCase c) {
        return Map.of(
                "id", c.id(),
                "title", c.title(),
                "description", c.description(),
                "hard", c.hard(),
                "mustBeClean", c.mustBeClean(),
                "expected", c.expected().stream()
                        .map(e -> Map.of(
                                "ruleCode", e.ruleCode().name(),
                                "targetObject", e.targetObject() == null ? "" : e.targetObject()))
                        .toList());
    }
}
