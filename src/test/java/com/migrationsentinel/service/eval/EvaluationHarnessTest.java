package com.migrationsentinel.service.eval;

import com.migrationsentinel.model.enums.EvaluationStatus;
import com.migrationsentinel.model.enums.ReviewMode;
import com.migrationsentinel.payload.request.RunEvaluationRequest;
import com.migrationsentinel.payload.response.EvaluationDetailResponse;
import com.migrationsentinel.payload.response.EvaluationRunResponse;
import com.migrationsentinel.support.SandboxTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The measured-improvement artifact. Runs the whole 15-case corpus through every point on
 * the improvement curve with the offline heuristic brain (deterministic, no API key), then
 * asserts each stage is at least as good as the one before it and the full agent strictly
 * beats the prompt-only baseline. The printed table is the source for docs/EVALUATION.md
 * and docs/CHANGELOG_IMPROVEMENT.md.
 */
@SandboxTest
@ActiveProfiles("standalone")
@SpringBootTest
class EvaluationHarnessTest {

    @Autowired
    private EvaluationService evaluationService;

    private static final List<ReviewMode> CURVE = List.of(
            ReviewMode.BASELINE_PROMPT,
            ReviewMode.ANALYZER_READ_ONLY,
            ReviewMode.ANALYZER_WITH_SANDBOX,
            ReviewMode.ANALYZER_VERIFIED,
            ReviewMode.ANALYZER_VERIFIER_SPLIT);

    @Test
    void improvementCurveHoldsAndFullAgentBeatsBaseline() {
        Map<ReviewMode, EvaluationRunResponse> results = new LinkedHashMap<>();
        for (ReviewMode mode : CURVE) {
            results.put(mode, runToCompletion(mode));
        }

        System.out.printf("%n%-28s  %6s %6s %6s %10s %8s%n", "stage", "P", "R", "F1", "FP/case", "passed");
        results.forEach((mode, r) -> System.out.printf("%-28s  %6.2f %6.2f %6.2f %10.2f %6d/%d%n",
                mode, nz(r.precision()), nz(r.recall()), nz(r.f1()), nz(r.falsePositiveRate()),
                passedCount(r.id()), r.totalCases()));

        EvaluationRunResponse baseline = results.get(ReviewMode.BASELINE_PROMPT);
        EvaluationRunResponse full = results.get(ReviewMode.ANALYZER_VERIFIER_SPLIT);

        assertThat(nz(full.recall())).isGreaterThan(nz(baseline.recall()));
        assertThat(nz(full.f1())).isGreaterThan(nz(baseline.f1()));
        assertThat(nz(full.falsePositiveRate())).isLessThan(nz(baseline.falsePositiveRate()));

        // Monotonic F1 along the curve (no stage regresses).
        double prev = -1;
        for (ReviewMode mode : CURVE) {
            double f1 = nz(results.get(mode).f1());
            assertThat(f1).as("F1 at %s should not regress", mode).isGreaterThanOrEqualTo(prev - 1e-9);
            prev = f1;
        }

        // The hard empty-vs-large pair: only the stages that ran the migration get case 04 right.
        EvaluationDetailResponse full04 = evaluationService.get(full.id());
        assertThat(full04.cases()).filteredOn(c -> c.caseId().equals("04-not-null-empty-table"))
                .allSatisfy(c -> assertThat(c.passed()).isTrue());
        assertThat(full04.cases()).filteredOn(c -> c.caseId().equals("03-not-null-large-table"))
                .allSatisfy(c -> assertThat(c.passed()).isTrue());
    }

    private long passedCount(java.util.UUID runId) {
        return evaluationService.get(runId).cases().stream().filter(c -> c.passed()).count();
    }

    private EvaluationRunResponse runToCompletion(ReviewMode mode) {
        EvaluationRunResponse started = evaluationService.submit(new RunEvaluationRequest(
                mode, "heuristic", List.of(), "harness-" + mode + "-" + Instant.now().toEpochMilli(), null));
        await().atMost(Duration.ofMinutes(15)).pollInterval(Duration.ofSeconds(3)).until(() -> {
            EvaluationStatus s = evaluationService.get(started.id()).run().status();
            return s == EvaluationStatus.COMPLETED || s == EvaluationStatus.FAILED;
        });
        EvaluationRunResponse done = evaluationService.get(started.id()).run();
        assertThat(done.status()).isEqualTo(EvaluationStatus.COMPLETED);
        return done;
    }

    private double nz(Double d) {
        return d == null ? 0.0 : d;
    }
}
