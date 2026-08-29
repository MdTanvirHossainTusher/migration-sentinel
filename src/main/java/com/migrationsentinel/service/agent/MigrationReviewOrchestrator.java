package com.migrationsentinel.service.agent;

import com.migrationsentinel.model.enums.AgentRole;
import com.migrationsentinel.model.enums.FindingVerdict;
import com.migrationsentinel.model.enums.ReviewMode;
import com.migrationsentinel.payload.dto.MigrationInput;
import com.migrationsentinel.payload.dto.ProposedFinding;
import com.migrationsentinel.payload.dto.SandboxRunResult;
import com.migrationsentinel.payload.dto.VerifiedFinding;
import com.migrationsentinel.service.ReviewReportRenderer;
import com.migrationsentinel.service.llm.LlmClient;
import com.migrationsentinel.service.llm.LlmClientRegistry;
import com.migrationsentinel.service.sandbox.SandboxManager;
import com.migrationsentinel.service.sandbox.SandboxSession;
import com.migrationsentinel.service.support.AgentJsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one review. The {@link ReviewMode} selects which point on the improvement curve
 * to reproduce — from a single tool-less prompt to the full analyzer + verifier split.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationReviewOrchestrator {

    private final SandboxManager sandboxManager;
    private final ToolboxFactory toolboxFactory;
    private final AgentLoop agentLoop;
    private final PromptLibrary prompts;
    private final AgentJson agentJson;
    private final LlmClientRegistry llmRegistry;
    private final ReviewReportRenderer reportRenderer;
    private final AgentJsonMapper mapper;

    public record Result(List<VerifiedFinding> findings, String reportMarkdown, boolean sandboxUsed,
                         SandboxRunResult sandboxRun) {
    }

    public Result review(MigrationInput input, TrajectoryRecorder recorder) {
        LlmClient client = llmRegistry.resolve(input.provider());
        ReviewMode mode = input.mode();
        boolean wantsSandbox = mode != ReviewMode.BASELINE_PROMPT;

        SandboxSession sandbox = null;
        Toolbox toolbox = null;
        SandboxRunResult sandboxRun = null;
        try {
            if (wantsSandbox && sandboxManager.dockerAvailable()) {
                sandbox = sandboxManager.start();
            } else if (wantsSandbox) {
                log.warn("Docker unavailable — mode {} degrades to structure-only", mode);
            }
            boolean applyCandidate = mode != ReviewMode.ANALYZER_READ_ONLY;
            toolbox = toolboxFactory.create(input, sandbox, applyCandidate);

            List<ProposedFinding> proposed = runAnalyzer(input, mode, client, toolbox, recorder);

            List<VerifiedFinding> verified = switch (mode) {
                case BASELINE_PROMPT, ANALYZER_READ_ONLY, ANALYZER_WITH_SANDBOX ->
                        proposed.stream().map(this::asUnverified).toList();
                case ANALYZER_VERIFIED -> runSelfVerification(input, client, toolbox, proposed, recorder);
                case ANALYZER_VERIFIER_SPLIT -> runVerifierAgent(input, client, toolbox, proposed, recorder);
            };

            if (toolbox != null && toolbox.sandboxAvailable()) {
                sandboxRun = toolbox.ensureCandidateRun();
            }
            boolean sandboxUsed = sandbox != null && sandboxRun != null
                    && (sandboxRun.baselineApplied() || sandboxRun.candidateApplied());

            String report = reportRenderer.render(input, verified, sandboxRun, sandboxUsed);
            return new Result(verified, report, sandboxUsed, sandboxRun);

        } finally {
            if (sandbox != null) {
                try {
                    sandbox.close();
                } catch (RuntimeException ex) {
                    log.warn("sandbox teardown failed: {}", ex.getMessage());
                }
            }
        }
    }

    private List<ProposedFinding> runAnalyzer(MigrationInput input, ReviewMode mode, LlmClient client,
                                              Toolbox toolbox, TrajectoryRecorder recorder) {
        if (mode == ReviewMode.BASELINE_PROMPT) {
            String raw = agentLoop.run(AgentRole.BASELINE, client,
                    prompts.baselineSystemPrompt(), prompts.reviewUserPrompt(input),
                    List.of(), recorder);
            return agentJson.parseFindings(raw);
        }
        boolean includeSandboxRun = mode != ReviewMode.ANALYZER_READ_ONLY;
        var specs = toolbox.specs(includeSandboxRun);
        String raw = agentLoop.run(AgentRole.ANALYZER, client,
                prompts.analyzerSystemPrompt(), prompts.reviewUserPrompt(input),
                specs, recorder);
        return agentJson.parseFindings(raw);
    }

    private List<VerifiedFinding> runSelfVerification(MigrationInput input, LlmClient client, Toolbox toolbox,
                                                      List<ProposedFinding> proposed, TrajectoryRecorder recorder) {
        if (proposed.isEmpty()) {
            return List.of();
        }
        String findingsJson = toJson(proposed);
        String raw = agentLoop.run(AgentRole.ANALYZER, client,
                prompts.verifierSystemPrompt(),
                prompts.verifierUserPrompt(input, findingsJson),
                toolbox.specs(true), recorder);
        return agentJson.applyVerdicts(proposed, raw);
    }

    private List<VerifiedFinding> runVerifierAgent(MigrationInput input, LlmClient client, Toolbox toolbox,
                                                   List<ProposedFinding> proposed, TrajectoryRecorder recorder) {
        if (proposed.isEmpty()) {
            return List.of();
        }
        String findingsJson = toJson(proposed);
        String raw = agentLoop.run(AgentRole.VERIFIER, client,
                prompts.verifierSystemPrompt(),
                prompts.verifierUserPrompt(input, findingsJson),
                toolbox.specs(true), recorder);
        return agentJson.applyVerdicts(proposed, raw);
    }

    private VerifiedFinding asUnverified(ProposedFinding f) {
        return new VerifiedFinding(f, FindingVerdict.UNVERIFIED, "no verification pass in this mode");
    }

    private String toJson(List<ProposedFinding> findings) {
        try {
            return mapper.writeValueAsString(java.util.Map.of("findings", findings));
        } catch (Exception e) {
            return "{\"findings\":[]}";
        }
    }
}
