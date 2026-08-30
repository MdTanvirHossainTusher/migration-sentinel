package com.migrationsentinel.service;

import com.migrationsentinel.config.properties.SentinelProperties;
import com.migrationsentinel.exception.BadResourceRequestException;
import com.migrationsentinel.exception.ResourceNotFoundException;
import com.migrationsentinel.mapper.DtoMapper;
import com.migrationsentinel.model.entity.ApprovalRecordEntity;
import com.migrationsentinel.model.entity.FindingEntity;
import com.migrationsentinel.payload.request.ApplyRewriteRequest;
import com.migrationsentinel.payload.response.ApprovalRecordResponse;
import com.migrationsentinel.aspect.Audited;
import com.migrationsentinel.repository.ApprovalRecordRepository;
import com.migrationsentinel.repository.FindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The single consequential action: write a finding's suggested rewrite to a file. It
 * only ever writes inside {@code sentinel.rewrite-output-dir}, never touches the original
 * migration, requires {@code confirm=true}, and records who approved it. Disabled unless
 * {@code sentinel.rewrite-apply-enabled=true}. See docs/SAFETY_MODEL.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewriteApplyService {

    private final FindingRepository findingRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final SentinelProperties properties;
    private final DtoMapper mapper;

    @Transactional
    @Audited(action = "rewrite.applied", aggregateType = "review", id = "reviewJobId")
    public ApprovalRecordResponse apply(ApplyRewriteRequest request) {
        if (!properties.isRewriteApplyEnabled()) {
            throw new BadResourceRequestException(
                    "Applying rewrites to disk is disabled. Set sentinel.rewrite-apply-enabled=true to allow it.");
        }
        if (!request.confirm()) {
            throw new BadResourceRequestException("confirm must be true to apply a rewrite.");
        }
        if (request.approvedBy() == null || request.approvedBy().isBlank()) {
            throw new BadResourceRequestException("approvedBy is required — the rewrite is recorded against this name.");
        }
        FindingEntity finding = findingRepository.findById(request.findingId())
                .orElseThrow(() -> new ResourceNotFoundException("Finding " + request.findingId() + " not found"));
        if (finding.getSuggestedRewrite() == null || finding.getSuggestedRewrite().isBlank()) {
            throw new BadResourceRequestException("This finding has no suggested rewrite to apply.");
        }

        Path outputDir = Path.of(properties.getRewriteOutputDir()).toAbsolutePath().normalize();
        Path target = outputDir.resolve(sanitize(request.targetFilename())).normalize();
        if (!target.startsWith(outputDir)) {
            throw new BadResourceRequestException("targetFilename must stay inside the rewrite output directory.");
        }

        ApprovalRecordEntity record = new ApprovalRecordEntity();
        record.setReviewJobId(finding.getReviewJob().getId());
        record.setFindingId(finding.getId());
        record.setAction("APPLY_REWRITE");
        record.setApprovedBy(request.approvedBy());
        record.setTargetPath(target.toString());
        record.setNote(request.note());

        try {
            Files.createDirectories(outputDir);
            Files.writeString(target,
                    "-- Suggested rewrite for finding " + finding.getId() + " (" + finding.getRuleCode() + ")\n"
                            + "-- Approved by " + request.approvedBy() + " via Migration Sentinel. Review before use.\n\n"
                            + finding.getSuggestedRewrite() + "\n",
                    StandardCharsets.UTF_8);
            record.setApplied(true);
            log.info("rewrite for finding {} written to {} (approved by {})",
                    finding.getId(), target, request.approvedBy());
        } catch (IOException ex) {
            record.setApplied(false);
            record.setNote((request.note() == null ? "" : request.note() + " | ") + "write failed: " + ex.getMessage());
        }
        ApprovalRecordEntity persisted = approvalRecordRepository.save(record);
        return mapper.toApprovalResponse(persisted);
    }

    @Transactional(readOnly = true)
    public List<ApprovalRecordResponse> history(UUID reviewJobId) {
        return approvalRecordRepository.findByReviewJobIdOrderByCreatedAtDesc(reviewJobId).stream()
                .map(mapper::toApprovalResponse).toList();
    }

    private String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
