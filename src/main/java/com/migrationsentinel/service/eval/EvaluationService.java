package com.migrationsentinel.service.eval;

import com.migrationsentinel.exception.ResourceNotFoundException;
import com.migrationsentinel.mapper.DtoMapper;
import com.migrationsentinel.model.entity.EvaluationRunEntity;
import com.migrationsentinel.model.enums.EvaluationStatus;
import com.migrationsentinel.payload.common.PageResult;
import com.migrationsentinel.payload.common.Pagination;
import com.migrationsentinel.payload.request.RunEvaluationRequest;
import com.migrationsentinel.payload.response.EvaluationDetailResponse;
import com.migrationsentinel.payload.response.EvaluationRunResponse;
import com.migrationsentinel.repository.EvaluationCaseResultRepository;
import com.migrationsentinel.repository.EvaluationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final EvaluationRunRepository evaluationRunRepository;
    private final EvaluationCaseResultRepository caseResultRepository;
    private final EvaluationRunner evaluationRunner;
    private final EvaluationCorpus corpus;
    private final DtoMapper mapper;

    @Transactional
    public EvaluationRunResponse submit(RunEvaluationRequest request) {
        EvaluationRunEntity run = new EvaluationRunEntity();
        run.setStatus(EvaluationStatus.QUEUED);
        run.setMode(request.modeOrDefault());
        run.setLlmProvider(request.provider() == null || request.provider().isBlank()
                ? "heuristic" : request.provider());
        run.setCorpusLabel(request.corpusLabel());
        run.setTotalCases(corpus.subset(request.caseIds()).size());
        run = evaluationRunRepository.saveAndFlush(run);

        evaluationRunner.runAsync(run.getId(), request.caseIds());
        return mapper.toEvaluationRunResponse(run);
    }

    @Transactional(readOnly = true)
    public EvaluationDetailResponse get(UUID id) {
        EvaluationRunEntity run = evaluationRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evaluation run " + id + " not found"));
        return new EvaluationDetailResponse(
                mapper.toEvaluationRunResponse(run),
                caseResultRepository.findByEvaluationRunIdOrderByCaseIdAsc(id).stream()
                        .map(mapper::toCaseResultResponse).toList());
    }

    @Transactional(readOnly = true)
    public PageResult<EvaluationRunResponse> list(int page, int size) {
        Page<EvaluationRunEntity> runs = evaluationRunRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return new PageResult<>(
                runs.getContent().stream().map(mapper::toEvaluationRunResponse).toList(),
                Pagination.from(runs));
    }

    @Transactional(readOnly = true)
    public List<EvaluationCase> cases() {
        return corpus.all();
    }
}
