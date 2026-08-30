package com.migrationsentinel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migrationsentinel.exception.BadResourceRequestException;
import com.migrationsentinel.exception.ResourceNotFoundException;
import com.migrationsentinel.model.entity.FindingEntity;
import com.migrationsentinel.model.entity.ReviewJobEntity;
import com.migrationsentinel.model.entity.ToolCallEntity;
import com.migrationsentinel.model.enums.ReviewStatus;
import com.migrationsentinel.mapper.DtoMapper;
import com.migrationsentinel.messaging.JobSubmissionGateway;
import com.migrationsentinel.service.audit.AuditService;
import com.migrationsentinel.payload.common.PageResult;
import com.migrationsentinel.payload.common.Pagination;
import com.migrationsentinel.payload.dto.MigrationFile;
import com.migrationsentinel.payload.request.CreateReviewRequest;
import com.migrationsentinel.payload.response.ReviewReportResponse;
import com.migrationsentinel.payload.response.ReviewResponse;
import com.migrationsentinel.repository.FindingRepository;
import com.migrationsentinel.repository.ReviewJobRepository;
import com.migrationsentinel.repository.ToolCallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewJobRepository reviewJobRepository;
    private final FindingRepository findingRepository;
    private final ToolCallRepository toolCallRepository;
    private final JobSubmissionGateway jobGateway;
    private final AuditService auditService;
    private final DtoMapper mapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReviewResponse submit(CreateReviewRequest request) {
        List<MigrationFile> history = MigrationHistory.ordered(request.baselineMigrations());
        long historyChars = MigrationHistory.totalLength(history);
        if (historyChars > CreateReviewRequest.MAX_HISTORY_CHARS) {
            throw new BadResourceRequestException("The migration history is "
                    + (historyChars / 1_000_000) + " MB, over the "
                    + (CreateReviewRequest.MAX_HISTORY_CHARS / 1_000_000) + " MB limit. "
                    + "Trim the oldest migrations, or squash them into a single baseline file.");
        }

        ReviewJobEntity job = new ReviewJobEntity();
        job.setStatus(ReviewStatus.QUEUED);
        job.setMode(request.modeOrDefault());
        job.setMigrationFilename(request.filename());
        job.setMigrationSql(request.migrationSql());
        // A supplied history wins over the pre-flattened field: it is the one we can order
        // ourselves and attribute a replay failure back to a filename.
        job.setBaselineSql(history.isEmpty()
                ? blankToNull(request.baselineSql())
                : MigrationHistory.concat(history));
        job.setBaselineFileCount(history.size());
        job.setBaselineFilesJson(toJsonArray(history));
        job.setTargetSchema(blankToNull(request.targetSchema()));
        job.setSeedSql(blankToNull(request.seedSql()));
        job.setEntitySource(blankToNull(request.entitySource()));
        job.setLlmProvider(request.provider() == null || request.provider().isBlank() ? "heuristic" : request.provider());
        job = reviewJobRepository.saveAndFlush(job);

        auditService.record("review.submitted", "review", job.getId().toString(), "api",
                "Review queued for " + (job.getMigrationFilename() == null ? "pasted SQL" : job.getMigrationFilename()),
                java.util.Map.of(
                        "mode", job.getMode().name(),
                        "provider", job.getLlmProvider(),
                        "baselineFileCount", job.getBaselineFileCount()));
        jobGateway.submitReview(job.getId());
        return mapper.toReviewResponse(job, List.of());
    }

    @Transactional(readOnly = true)
    public ReviewResponse get(UUID id) {
        ReviewJobEntity job = load(id);
        return mapper.toReviewResponse(job, findingRepository.findByReviewJobIdOrderByOrdinalAsc(id));
    }

    @Transactional(readOnly = true)
    public ReviewReportResponse getReport(UUID id) {
        ReviewJobEntity job = load(id);
        List<FindingEntity> findings = findingRepository.findByReviewJobIdOrderByOrdinalAsc(id);
        List<ToolCallEntity> trajectory = toolCallRepository.findByReviewJobIdOrderByStepNoAsc(id);
        return new ReviewReportResponse(
                mapper.toReviewResponse(job, findings),
                job.getReportMarkdown(),
                findings.stream().map(mapper::toFindingResponse).toList(),
                trajectory.stream().map(mapper::toToolCallResponse).toList());
    }

    @Transactional(readOnly = true)
    public PageResult<ReviewResponse> list(int page, int size) {
        Page<ReviewJobEntity> jobs = reviewJobRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        List<ReviewResponse> data = jobs.getContent().stream()
                .map(j -> mapper.toReviewResponse(j,
                        findingRepository.findByReviewJobIdOrderByOrdinalAsc(j.getId())))
                .toList();
        return new PageResult<>(data, Pagination.from(jobs));
    }

    private ReviewJobEntity load(UUID id) {
        return reviewJobRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review " + id + " not found"));
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Ordered filenames only — the SQL itself already lives in {@code baseline_sql}. */
    private String toJsonArray(List<MigrationFile> history) {
        if (history.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(history.stream().map(MigrationFile::filename).toList());
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
