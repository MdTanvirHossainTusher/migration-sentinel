package com.migrationsentinel.service;

import com.migrationsentinel.exception.ResourceNotFoundException;
import com.migrationsentinel.model.entity.FindingEntity;
import com.migrationsentinel.model.entity.ReviewJobEntity;
import com.migrationsentinel.model.entity.ToolCallEntity;
import com.migrationsentinel.model.enums.ReviewStatus;
import com.migrationsentinel.mapper.DtoMapper;
import com.migrationsentinel.payload.common.PageResult;
import com.migrationsentinel.payload.common.Pagination;
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
    private final ReviewRunner reviewRunner;
    private final DtoMapper mapper;

    @Transactional
    public ReviewResponse submit(CreateReviewRequest request) {
        ReviewJobEntity job = new ReviewJobEntity();
        job.setStatus(ReviewStatus.QUEUED);
        job.setMode(request.modeOrDefault());
        job.setMigrationFilename(request.filename());
        job.setMigrationSql(request.migrationSql());
        job.setBaselineSql(blankToNull(request.baselineSql()));
        job.setSeedSql(blankToNull(request.seedSql()));
        job.setEntitySource(blankToNull(request.entitySource()));
        job.setLlmProvider(request.provider() == null || request.provider().isBlank() ? "heuristic" : request.provider());
        job = reviewJobRepository.saveAndFlush(job);

        reviewRunner.runAsync(job.getId());
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
}
