package com.migrationsentinel.controller;

import com.migrationsentinel.payload.common.ApiResponse;
import com.migrationsentinel.payload.common.PageResult;
import com.migrationsentinel.payload.common.ResponseBuilder;
import com.migrationsentinel.payload.request.ApplyRewriteRequest;
import com.migrationsentinel.payload.request.CreateReviewRequest;
import com.migrationsentinel.payload.response.ApprovalRecordResponse;
import com.migrationsentinel.payload.response.ReviewReportResponse;
import com.migrationsentinel.payload.response.ReviewResponse;
import com.migrationsentinel.service.RewriteApplyService;
import com.migrationsentinel.service.ReviewService;
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
import java.util.UUID;

import static com.migrationsentinel.constant.code.SuccessCodes.REVIEW_ACCEPTED;
import static com.migrationsentinel.constant.code.SuccessCodes.REWRITE_APPLIED;

@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "Submit migrations for agentic safety review and read the reports")
public class ReviewController {

    private final ReviewService reviewService;
    private final RewriteApplyService rewriteApplyService;

    @PostMapping
    @Operation(summary = "Submit a candidate migration for review (runs asynchronously)")
    public ResponseEntity<ApiResponse<ReviewResponse>> submit(@Valid @RequestBody CreateReviewRequest request) {
        return ResponseBuilder.accepted(reviewService.submit(request), REVIEW_ACCEPTED,
                "Review queued. Poll GET /api/v1/reviews/{id} for status.");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Review status and summary counts")
    public ResponseEntity<ApiResponse<ReviewResponse>> get(@PathVariable UUID id) {
        return ResponseBuilder.ok(reviewService.get(id));
    }

    @GetMapping("/{id}/report")
    @Operation(summary = "Full review report: findings, evidence, Markdown and the agent trajectory")
    public ResponseEntity<ApiResponse<ReviewReportResponse>> report(@PathVariable UUID id) {
        return ResponseBuilder.ok(reviewService.getReport(id));
    }

    @GetMapping
    @Operation(summary = "List recent reviews")
    public ResponseEntity<ApiResponse<List<ReviewResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<ReviewResponse> result = reviewService.list(page, size);
        return ResponseBuilder.ok(result.data(), result.pagination());
    }

    @PostMapping("/rewrites/apply")
    @Operation(summary = "Human-approved: write a finding's suggested rewrite to a file (never edits the original)")
    public ResponseEntity<ApiResponse<ApprovalRecordResponse>> applyRewrite(
            @Valid @RequestBody ApplyRewriteRequest request) {
        return ResponseBuilder.ok(rewriteApplyService.apply(request), REWRITE_APPLIED,
                "Rewrite written to the configured output directory.");
    }

    @GetMapping("/{id}/approvals")
    @Operation(summary = "Approval / apply-rewrite audit trail for a review")
    public ResponseEntity<ApiResponse<List<ApprovalRecordResponse>>> approvals(@PathVariable UUID id) {
        return ResponseBuilder.ok(rewriteApplyService.history(id));
    }
}
