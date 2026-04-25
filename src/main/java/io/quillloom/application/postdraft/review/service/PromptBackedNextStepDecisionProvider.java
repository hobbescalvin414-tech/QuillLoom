package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermEntry;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermsProposal;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.prompt.InvestigationPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.ReviewAgentSystemPromptBuilder;
import io.quillloom.domain.shared.TermTextNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PromptBackedNextStepDecisionProvider {

    private static final int MAX_REPAIR_ATTEMPTS = 5;
    private static final Pattern CONFIRMED_TERM_PATTERN = Pattern.compile("confirmedTerm=([^\\-]+)->(.+)");
    private static final Pattern CONFIRMED_TERM_UPDATES_PATTERN = Pattern.compile("confirmedTermUpdates=\\{([^}]*)}");

    private final InvestigationPromptBuilder promptBuilder;
    private final ReviewAgentSystemPromptBuilder systemPromptBuilder;
    private final ReviewToolRegistry toolRegistry;
    private final ReviewAgentStructuredGenerationPort generationPort;
    private final ReviewToolDecisionContractValidator contractValidator;
    private final ReviewAgentPromptDumpWriter promptDumpWriter;
    private final ThreadLocal<InvestigationPromptBuilder.PromptProjectState> runtimePromptState = new ThreadLocal<>();

    public PromptBackedNextStepDecisionProvider(InvestigationPromptBuilder promptBuilder,
                                                ReviewToolRegistry toolRegistry,
                                                ReviewAgentStructuredGenerationPort generationPort) {
        this(promptBuilder,
                new ReviewAgentSystemPromptBuilder(),
                toolRegistry,
                generationPort,
                new ReviewToolDecisionContractValidator(),
                ReviewAgentPromptDumpWriter.disabled());
    }

    public PromptBackedNextStepDecisionProvider(InvestigationPromptBuilder promptBuilder,
                                                ReviewToolRegistry toolRegistry,
                                                ReviewAgentStructuredGenerationPort generationPort,
                                                ReviewAgentPromptDumpWriter promptDumpWriter) {
        this(promptBuilder,
                new ReviewAgentSystemPromptBuilder(),
                toolRegistry,
                generationPort,
                new ReviewToolDecisionContractValidator(),
                promptDumpWriter);
    }

    public PromptBackedNextStepDecisionProvider(InvestigationPromptBuilder promptBuilder,
                                                ReviewAgentSystemPromptBuilder systemPromptBuilder,
                                                ReviewToolRegistry toolRegistry,
                                                ReviewAgentStructuredGenerationPort generationPort,
                                                ReviewToolDecisionContractValidator contractValidator) {
        this(promptBuilder,
                systemPromptBuilder,
                toolRegistry,
                generationPort,
                contractValidator,
                ReviewAgentPromptDumpWriter.disabled());
    }

    public PromptBackedNextStepDecisionProvider(InvestigationPromptBuilder promptBuilder,
                                                ReviewAgentSystemPromptBuilder systemPromptBuilder,
                                                ReviewToolRegistry toolRegistry,
                                                ReviewAgentStructuredGenerationPort generationPort,
                                                ReviewToolDecisionContractValidator contractValidator,
                                                ReviewAgentPromptDumpWriter promptDumpWriter) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.systemPromptBuilder = Objects.requireNonNull(systemPromptBuilder, "systemPromptBuilder");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.generationPort = Objects.requireNonNull(generationPort, "generationPort");
        this.contractValidator = Objects.requireNonNull(contractValidator, "contractValidator");
        this.promptDumpWriter = Objects.requireNonNull(promptDumpWriter, "promptDumpWriter");
    }

    public ReviewToolDecision decide(PostDraftReviewSession session) {
        Objects.requireNonNull(session, "session");
        String systemPrompt = systemPromptBuilder.build(toolRegistry.definitions());
        String originalInvestigationPrompt = promptBuilder.build(
                session,
                toolRegistry.definitions(),
                session.evidenceSummaries(),
                currentPromptProjectState()
        );
        RepairLoopState state = RepairLoopState.start(systemPrompt, originalInvestigationPrompt);
        dumpPrompt(session, systemPrompt, state.userPrompt(), state.promptKind(), 0, null, null, null, null, null, "PromptCapture");

        int repairsUsed = 0;
        while (true) {
            LoopOutcome outcome = executeCurrentStage(session, state, repairsUsed);
            if (outcome.finalDecision() != null) {
                return outcome.finalDecision();
            }
            state = outcome.nextState();
            repairsUsed = outcome.repairsUsed();
        }
    }

    public ReviewToolDecision decide(ProjectReviewRuntimeSession runtime, PostDraftReviewSession session) {
        Objects.requireNonNull(runtime, "runtime");
        runtimePromptState.set(new InvestigationPromptBuilder.PromptProjectState(
                runtime.pendingChunkCount(),
                runtime.completedChunkCount(),
                runtime.currentFocusChunkStillPending()
        ));
        try {
            return decide(session);
        } finally {
            runtimePromptState.remove();
        }
    }

    private LoopOutcome executeCurrentStage(PostDraftReviewSession session,
                                            RepairLoopState state,
                                            int repairsUsed) {
        return switch (state.stage()) {
            case NEXT_STEP -> executeNextStepStage(session, state, repairsUsed);
            case RECORD_CONFIRMED_TERMS_PROPOSAL -> executeProposalStage(session, state, repairsUsed);
        };
    }

    private LoopOutcome executeNextStepStage(PostDraftReviewSession session,
                                             RepairLoopState state,
                                             int repairsUsed) {
        ReviewToolDecision decision;
        try {
            decision = generationPort.generateNextToolDecision(state.systemPrompt(), state.userPrompt());
        } catch (LlmStructuredOutputException ex) {
            if (!isRepairableStructuredOutputError(ex) || repairsUsed >= MAX_REPAIR_ATTEMPTS) {
                dumpPrompt(
                        session,
                        state.systemPrompt(),
                        state.userPrompt(),
                        state.promptKind(),
                        repairsUsed,
                        null,
                        null,
                        ex.getMessage(),
                        ex.getMessage(),
                        extractRawOutput(ex.getMessage()),
                        ex.getClass().getSimpleName()
                );
                throw new ReviewAgentNextStepStructuredOutputException(
                        "Review agent next-step structured output failed: " + ex.getMessage(),
                        ex
                );
            }
            int nextRepairAttempt = repairsUsed + 1;
            String repairPrompt = buildStructuredOutputRepairPrompt(session, state.originalInvestigationPrompt(), ex.getMessage());
            dumpPrompt(
                    session,
                    state.systemPrompt(),
                    repairPrompt,
                    "structured_output_repair",
                    nextRepairAttempt,
                    null,
                    null,
                    ex.getMessage(),
                    ex.getMessage(),
                    extractRawOutput(ex.getMessage()),
                    "PromptCapture"
            );
            return LoopOutcome.repair(
                    state.toNextStepPrompt(repairPrompt, "structured_output_repair"),
                    nextRepairAttempt
            );
        }

        Optional<String> validationError = contractValidator.validate(decision, toolRegistry);
        if (validationError.isPresent()) {
            String error = validationError.orElseThrow();
            if (repairsUsed >= MAX_REPAIR_ATTEMPTS) {
                dumpPrompt(
                        session,
                        state.systemPrompt(),
                        state.userPrompt(),
                        state.promptKind(),
                        repairsUsed,
                        decision.toolName(),
                        error,
                        decision.reason(),
                        null,
                        decision.toString(),
                        ReviewAgentNextStepStructuredOutputException.class.getSimpleName()
                );
                throw new ReviewAgentNextStepStructuredOutputException(
                        "Review agent next-step structured output failed: invalid tool decision after repair budget exhausted: "
                                + error + "; rawOutput=" + decision,
                        null
                );
            }
            int nextRepairAttempt = repairsUsed + 1;
            String repairPrompt = buildDecisionRepairPrompt(session, state.originalInvestigationPrompt(), decision, error);
            dumpPrompt(
                    session,
                    state.systemPrompt(),
                    repairPrompt,
                    "decision_repair",
                    nextRepairAttempt,
                    decision.toolName(),
                    error,
                    decision.reason(),
                    null,
                    decision.toString(),
                    "PromptCapture"
            );
            return LoopOutcome.repair(
                    state.toNextStepPrompt(repairPrompt, "decision_repair"),
                    nextRepairAttempt
            );
        }

        if (!"record_confirmed_terms".equals(decision.toolName())) {
            return LoopOutcome.finalDecision(decision, repairsUsed);
        }

        List<RecordConfirmedTermEntry> stablePairSignals = collectStablePairSignals(session);
        if (stablePairSignals.isEmpty()) {
            return LoopOutcome.finalDecision(decision, repairsUsed);
        }

        String proposalPrompt = buildRecordConfirmedTermsProposalPrompt(session, stablePairSignals);
        dumpPrompt(
                session,
                state.systemPrompt(),
                proposalPrompt,
                "record_confirmed_terms_proposal",
                repairsUsed,
                "record_confirmed_terms",
                null,
                null,
                null,
                null,
                "PromptCapture"
        );
        return LoopOutcome.repair(
                state.toProposalPrompt(proposalPrompt),
                repairsUsed
        );
    }

    private LoopOutcome executeProposalStage(PostDraftReviewSession session,
                                             RepairLoopState state,
                                             int repairsUsed) {
        try {
            RecordConfirmedTermsProposal proposal = generationPort.generateRecordConfirmedTermsProposal(
                    state.systemPrompt(),
                    state.userPrompt()
            );
            return handleProposalResult(session, state, proposal, repairsUsed);
        } catch (LlmStructuredOutputException ex) {
            if (repairsUsed >= MAX_REPAIR_ATTEMPTS) {
                dumpPrompt(
                        session,
                        state.systemPrompt(),
                        state.userPrompt(),
                        state.promptKind(),
                        repairsUsed,
                        "record_confirmed_terms",
                        null,
                        ex.getMessage(),
                        ex.getMessage(),
                        extractRawOutput(ex.getMessage()),
                        ex.getClass().getSimpleName()
                );
                throw new RecordConfirmedTermsProposalException(
                        "Review agent record_confirmed_terms proposal failed: " + ex.getMessage(),
                        ex
                );
            }
            int nextRepairAttempt = repairsUsed + 1;
            String repairPrompt = buildRecordConfirmedTermsProposalRepairPrompt(
                    session,
                    state.originalProposalPrompt(),
                    "proposal_structured_output_error",
                    ex.getMessage(),
                    extractRawOutput(ex.getMessage())
            );
            dumpPrompt(
                    session,
                    state.systemPrompt(),
                    repairPrompt,
                    "record_confirmed_terms_proposal_repair",
                    nextRepairAttempt,
                    "record_confirmed_terms",
                    null,
                    ex.getMessage(),
                    ex.getMessage(),
                    extractRawOutput(ex.getMessage()),
                    "PromptCapture"
            );
            return LoopOutcome.repair(
                    state.toProposalRepairPrompt(repairPrompt),
                    nextRepairAttempt
            );
        }
    }

    private LoopOutcome handleProposalResult(PostDraftReviewSession session,
                                             RepairLoopState state,
                                             RecordConfirmedTermsProposal proposal,
                                             int repairsUsed) {
        if (proposal.action() == RecordConfirmedTermsProposal.Action.NOT_APPLICABLE) {
            if (repairsUsed >= MAX_REPAIR_ATTEMPTS) {
                throw new RecordConfirmedTermsProposalException(
                        "Review agent record_confirmed_terms proposal failed: NOT_APPLICABLE after next-step selected record_confirmed_terms; reason="
                                + proposal.reason(),
                        new IllegalStateException("proposal_not_applicable")
                );
            }
            int nextRepairAttempt = repairsUsed + 1;
            String replanPrompt = buildProposalNotApplicableReplanPrompt(
                    session,
                    state.originalInvestigationPrompt(),
                    proposal
            );
            dumpPrompt(
                    session,
                    state.systemPrompt(),
                    replanPrompt,
                    "decision_replan_after_proposal",
                    nextRepairAttempt,
                    "record_confirmed_terms",
                    "proposal_not_applicable",
                    proposal.reason(),
                    null,
                    proposal.toString(),
                    "PromptCapture"
            );
            return LoopOutcome.repair(
                    state.toNextStepPrompt(replanPrompt, "decision_replan_after_proposal"),
                    nextRepairAttempt
            );
        }
        try {
            return LoopOutcome.finalDecision(assembleRecordConfirmedTermsDecision(proposal), repairsUsed);
        } catch (RecordConfirmedTermsAssemblyException ex) {
            if (repairsUsed >= MAX_REPAIR_ATTEMPTS) {
                throw ex;
            }
            int nextRepairAttempt = repairsUsed + 1;
            String repairPrompt = buildRecordConfirmedTermsProposalRepairPrompt(
                    session,
                    state.originalProposalPrompt(),
                    "proposal_assembly_error",
                    ex.getMessage(),
                    proposal.toString()
            );
            dumpPrompt(
                    session,
                    state.systemPrompt(),
                    repairPrompt,
                    "record_confirmed_terms_proposal_repair",
                    nextRepairAttempt,
                    "record_confirmed_terms",
                    null,
                    ex.getMessage(),
                    null,
                    proposal.toString(),
                    "PromptCapture"
            );
            return LoopOutcome.repair(
                    state.toProposalRepairPrompt(repairPrompt),
                    nextRepairAttempt
            );
        }
    }

    private void dumpPrompt(PostDraftReviewSession session,
                            String systemPrompt,
                            String userPrompt,
                            String promptKind,
                            int attempt,
                            String toolName,
                            String validationError,
                            String errorMessage,
                            String structuredOutputError,
                            String rawOutput,
                            String exceptionType) {
        promptDumpWriter.dump(new ReviewAgentPromptDumpWriter.PromptDumpRecord(
                session.projectId(),
                promptKind,
                attempt,
                session.focus().chunkId(),
                session.workingSet().chunkIds().toString(),
                session.workingSetContext().snapshots().stream()
                        .map(io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot::chunkId)
                        .toList()
                        .toString(),
                session.workingSetContext().snapshots().stream()
                        .map(snapshot -> snapshot.chunkId() + ":" + classifySnapshotSource(session, snapshot.chunkId(), snapshot.anchor()))
                        .toList()
                        .toString(),
                0,
                toolName,
                validationError,
                errorMessage,
                structuredOutputError,
                rawOutput,
                exceptionType,
                systemPrompt,
                userPrompt
        ));
    }

    private String classifySnapshotSource(PostDraftReviewSession session,
                                          String chunkId,
                                          boolean anchor) {
        if (anchor) {
            return "anchor";
        }
        if (session.boundaryWindow().snapshots().stream().anyMatch(snapshot -> snapshot.chunkId().equals(chunkId))) {
            return "boundary_expansion";
        }
        return "block_expansion";
    }

    private boolean isRepairableStructuredOutputError(LlmStructuredOutputException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("structured generation output cannot be parsed as structured JSON")
                || message.contains("invalid structured tool decision")
                || message.contains("proposal rawOutput=");
    }

    private String buildDecisionRepairPrompt(PostDraftReviewSession session,
                                             String originalPrompt,
                                             ReviewToolDecision invalidDecision,
                                             String validationError) {
        String anchorChunkId = session.focus().chunkId();
        String argumentRequirements = toolRegistry.contains(invalidDecision.toolName())
                ? toolRegistry.require(invalidDecision.toolName()).renderArgumentRequirements()
                : "(unknown)";
        String argumentExample = toolRegistry.contains(invalidDecision.toolName())
                ? toolRegistry.require(invalidDecision.toolName()).renderArgumentsExample()
                : "(unknown)";
        return originalPrompt + """

                [Decision Repair]
                The previous tool decision is invalid. Return one valid JSON object that fixes the decision in one shot.
                - invalidToolName: %s
                - validationError: %s
                - previousArguments: %s
                - anchorChunkId: %s
                - currentWorkingSet: %s
                - toolArgumentRequirements: %s
                - toolArgumentsExample: %s

                Rules:
                1. Return JSON only. Do not add explanation outside JSON.
                2. If you keep the same tool, satisfy all required arguments in this response.
                3. `arguments` may contain only fields declared by the selected tool.
                4. If the selected tool has no arguments, return "arguments": {}.
                5. If you switch tools, the new `toolName`, `arguments`, and `reason` must already be valid together.

                Additional rule for complete_working_set:
                - `chunkIds` must include anchorChunkId=%s.
                - `chunkIds` must come from currentWorkingSet=%s.
                - do not submit chunks that were only read as context evidence.
                """.formatted(
                invalidDecision.toolName(),
                validationError,
                invalidDecision.arguments(),
                anchorChunkId,
                session.workingSet().chunkIds(),
                argumentRequirements,
                argumentExample,
                anchorChunkId,
                session.workingSet().chunkIds()
        ) + nextStepEntriesCompatibilityRepairGuidance(validationError);
    }

    private String buildProposalNotApplicableReplanPrompt(PostDraftReviewSession session,
                                                          String originalPrompt,
                                                          RecordConfirmedTermsProposal proposal) {
        return originalPrompt + """

                [Decision Replan]
                The previous record_confirmed_terms proposal is not applicable for the current focus.
                - validationError: proposal_not_applicable
                - proposalReason: %s
                - proposalEntries: %s
                - anchorChunkId: %s
                - currentWorkingSet: %s

                Replan the ordinary next step for the same focus.
                Rules:
                1. Return one valid tool decision JSON object only.
                2. Do not return proposal DTO fields such as action / entries array.
                3. Do not use proposal NOT_APPLICABLE as permission to force a different route without valid tool arguments.
                4. If evidence is still insufficient for record_confirmed_terms, choose another valid investigation or evaluation step.
                """.formatted(
                proposal.reason(),
                proposal.entries(),
                session.focus().chunkId(),
                session.workingSet().chunkIds()
        );
    }

    private String buildRecordConfirmedTermsProposalPrompt(PostDraftReviewSession session,
                                                           List<RecordConfirmedTermEntry> stablePairSignals) {
        String originalPrompt = promptBuilder.build(
                session,
                toolRegistry.definitions(),
                session.evidenceSummaries(),
                currentPromptProjectState()
        );
        String signalText = stablePairSignals.stream()
                .map(entry -> "- " + entry.sourceTerm() + " -> " + entry.targetTerm())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("- (none)");
        return originalPrompt + """

                [Record Confirmed Terms Proposal]
                You are now deciding only whether the current focus should record confirmed terms.
                Return one JSON object with:
                - action: RECORD_CONFIRMED_TERMS or NOT_APPLICABLE
                - reason: short justification
                - entries: array of {"sourceTerm":"...","targetTerm":"..."}

                Narrow routing constraints:
                1. This path is entered only because the current focus already has high-weight stable pair signals.
                2. Low-priority signals such as decisionNotes / translatorCommentary / transitionNote / confirmedTermLookupMiss do not independently justify recording.
                3. If the current focus does not support recording, return action=NOT_APPLICABLE and entries=[].
                4. If action=RECORD_CONFIRMED_TERMS, entries must be non-empty and every pair must stay inside the current workingSet evidence scope.
                5. Keep pair extraction explicit. Do not explain pairs only in reason.

                Current stable pair signals:
                %s
                """.formatted(signalText);
    }

    private InvestigationPromptBuilder.PromptProjectState currentPromptProjectState() {
        InvestigationPromptBuilder.PromptProjectState state = runtimePromptState.get();
        if (state != null) {
            return state;
        }
        return new InvestigationPromptBuilder.PromptProjectState(-1, -1, true);
    }

    private String buildRecordConfirmedTermsProposalRepairPrompt(PostDraftReviewSession session,
                                                                 String originalPrompt,
                                                                 String errorType,
                                                                 String errorMessage,
                                                                 String rawOutput) {
        return originalPrompt + """

                [Record Confirmed Terms Proposal Repair]
                The previous proposal output is not usable.
                - proposalErrorType: %s
                - proposalErrorMessage: %s
                - rawOutput: %s
                - anchorChunkId: %s
                - currentWorkingSet: %s

                Return exactly one valid JSON object for proposal only:
                - action: RECORD_CONFIRMED_TERMS or NOT_APPLICABLE
                - reason: short justification
                - entries: [{"sourceTerm":"...","targetTerm":"..."}]

                Rules:
                1. Do not return final tool arguments.
                2. Do not return arguments.entries map here.
                3. If action=RECORD_CONFIRMED_TERMS, entries must be non-empty.
                4. If action=NOT_APPLICABLE, entries must be [].
                5. Keep pair extraction explicit and conflict-free.
                """.formatted(
                errorType,
                errorMessage == null ? "(none)" : errorMessage,
                rawOutput == null ? "(none)" : rawOutput,
                session.focus().chunkId(),
                session.workingSet().chunkIds()
        );
    }

    private ReviewToolDecision assembleRecordConfirmedTermsDecision(RecordConfirmedTermsProposal proposal) {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        for (RecordConfirmedTermEntry entry : proposal.entries()) {
            String sourceTerm = entry.sourceTerm().trim();
            String targetTerm = entry.targetTerm().trim();
            String sourceKey = TermTextNormalizer.keyText(sourceTerm);
            String existing = entries.get(sourceTerm);
            if (existing != null && !existing.equals(targetTerm)) {
                throw new RecordConfirmedTermsAssemblyException(
                        "Review agent proposal -> decision assembly failed: conflicting_target_for_source:" + sourceTerm
                );
            }
            String existingByKey = entries.entrySet().stream()
                    .filter(candidate -> TermTextNormalizer.keyText(candidate.getKey()).equals(sourceKey))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            if (existingByKey != null && !existingByKey.equals(targetTerm)) {
                throw new RecordConfirmedTermsAssemblyException(
                        "Review agent proposal -> decision assembly failed: conflicting_target_for_source:" + sourceTerm
                );
            }
            entries.putIfAbsent(sourceTerm, targetTerm);
        }
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("entries", new LinkedHashMap<>(entries));
        return new ReviewToolDecision("record_confirmed_terms", arguments, proposal.reason());
    }

    private List<RecordConfirmedTermEntry> collectStablePairSignals(PostDraftReviewSession session) {
        LinkedHashMap<String, RecordConfirmedTermEntry> entries = new LinkedHashMap<>();
        for (String text : collectHighWeightEvidenceTexts(session)) {
            collectConfirmedTermSignals(entries, text);
            collectConfirmedTermUpdatesSignals(entries, text);
        }
        return List.copyOf(entries.values());
    }

    private List<String> collectHighWeightEvidenceTexts(PostDraftReviewSession session) {
        ArrayList<String> texts = new ArrayList<>();
        texts.addAll(session.readContextSummaries());
        texts.addAll(session.evidenceSummaries());
        texts.addAll(session.keyEvidenceSummaries());
        texts.addAll(session.transcriptStore().replay());
        session.toolTraces().forEach(trace -> texts.addAll(trace.notes()));
        return texts;
    }

    private void collectConfirmedTermSignals(LinkedHashMap<String, RecordConfirmedTermEntry> entries, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = CONFIRMED_TERM_PATTERN.matcher(text.trim());
        if (!matcher.find()) {
            return;
        }
        rememberStablePair(entries, matcher.group(1), matcher.group(2));
    }

    private void collectConfirmedTermUpdatesSignals(LinkedHashMap<String, RecordConfirmedTermEntry> entries, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = CONFIRMED_TERM_UPDATES_PATTERN.matcher(text);
        if (!matcher.find()) {
            return;
        }
        String[] pairs = matcher.group(1).split(",");
        for (String pair : pairs) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            rememberStablePair(entries, parts[0], parts[1]);
        }
    }

    private void rememberStablePair(LinkedHashMap<String, RecordConfirmedTermEntry> entries,
                                    String sourceTerm,
                                    String targetTerm) {
        String normalizedSource = TermTextNormalizer.displayText(sourceTerm);
        String normalizedTarget = TermTextNormalizer.displayText(targetTerm);
        if (normalizedSource.isBlank() || normalizedTarget.isBlank()) {
            return;
        }
        String pairKey = TermTextNormalizer.pairKey(normalizedSource, normalizedTarget);
        entries.putIfAbsent(pairKey, new RecordConfirmedTermEntry(normalizedSource, normalizedTarget));
    }

    private String buildStructuredOutputRepairPrompt(PostDraftReviewSession session,
                                                     String originalPrompt,
                                                     String errorMessage) {
        String toolArgumentSummary = toolRegistry.definitions().stream()
                .map(d -> d.toolName() + ": arguments=" + d.renderArgumentsExample()
                        + (d.renderArgumentRequirements().isBlank() ? "" : ", " + d.renderArgumentRequirements()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("(no tool argument summary)");
        return originalPrompt + """

                [Structured Output Repair]
                The previous response could not be parsed as a valid JSON tool decision. Return one valid JSON object.
                - structuredOutputError: %s
                - anchorChunkId: %s
                - currentWorkingSet: %s
                - toolArgumentSummary: %s

                Rules:
                1. Return JSON only. Do not add explanation outside JSON.
                2. `arguments` must be an object. Do not return `null`.
                3. If you choose `complete_working_set`, include `chunkIds`, and `chunkIds` must include the current anchorChunkId.
                4. If you choose `read_previous_chunks` or `read_next_chunks`, include `count`.
                5. `arguments` may contain only fields declared by the selected tool.
                6. If the selected tool has no arguments, return "arguments": {}.
                7. Do not return a partial fix. The final `toolName`, `arguments`, and `reason` must already be valid together.
                """.formatted(
                errorMessage == null ? "(none)" : errorMessage,
                session.focus().chunkId(),
                session.workingSet().chunkIds(),
                toolArgumentSummary
        ) + nextStepEntriesCompatibilityRepairGuidance(errorMessage);
    }

    private String nextStepEntriesCompatibilityRepairGuidance(String errorMessage) {
        if (!isEntriesRepairError(errorMessage)) {
            return "";
        }
        return """

                [entries repair]
                invalid_argument:entries must be repaired with exactly one of the following two valid outputs.
                errorLocation: arguments.entries

                Option A: keep record_confirmed_terms
                - toolName must still be record_confirmed_terms
                - arguments.entries must be a non-empty object{string:string}
                - valid example: {"entries":{"<source-term>":"<target-term>"}}
                - if reason already contains explicit source->target term pair, you must copy the same pair into arguments.entries
                - candidate pairs cannot appear only in reason
                - reason already contains explicit pair + entries={} is forbidden

                Option B: stop using record_confirmed_terms
                - switch to an allowed investigation/evaluation tool: read_previous_chunks / read_next_chunks / expand_block_context / lookup_knowledge_cards / read_confirmed_terms / evaluate_focus
                - the new tool arguments must be valid in one shot and may only contain arguments declared by that tool

                Forbidden third output:
                - {"entries": {}}
                - {"entries":{"sourceTerm":"...","targetTerm":"..."}}
                - {"entries":[{"sourceTerm":"...","targetTerm":"..."}]}
                - {"entries":["A=B"]}
                - explanation about why you stopped, but tool/arguments are still invalid
                - union/schema/argument-conflict analysis in reason
                - extra explanatory text outside JSON

                If you keep record_confirmed_terms, provide at least one explicit source->target term pair in arguments.entries.
                If current evidence is not enough to provide explicit term pairs, choose Option B and do not force entries.
                """;
    }

    private boolean isEntriesRepairError(String errorMessage) {
        return errorMessage != null && errorMessage.contains("invalid_argument:entries");
    }

    private String extractRawOutput(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        int rawOutputIndex = errorMessage.indexOf("rawOutput=");
        if (rawOutputIndex < 0) {
            return null;
        }
        return errorMessage.substring(rawOutputIndex + "rawOutput=".length()).trim();
    }

    private enum RepairStage {
        NEXT_STEP,
        RECORD_CONFIRMED_TERMS_PROPOSAL
    }

    private record RepairLoopState(RepairStage stage,
                                   String systemPrompt,
                                   String originalInvestigationPrompt,
                                   String originalProposalPrompt,
                                   String userPrompt,
                                   String promptKind) {

        private static RepairLoopState start(String systemPrompt, String originalInvestigationPrompt) {
            return new RepairLoopState(
                    RepairStage.NEXT_STEP,
                    systemPrompt,
                    originalInvestigationPrompt,
                    null,
                    originalInvestigationPrompt,
                    "investigation"
            );
        }

        private RepairLoopState toNextStepPrompt(String nextPrompt, String nextPromptKind) {
            return new RepairLoopState(
                    RepairStage.NEXT_STEP,
                    systemPrompt,
                    originalInvestigationPrompt,
                    originalProposalPrompt,
                    nextPrompt,
                    nextPromptKind
            );
        }

        private RepairLoopState toProposalPrompt(String proposalPrompt) {
            return new RepairLoopState(
                    RepairStage.RECORD_CONFIRMED_TERMS_PROPOSAL,
                    systemPrompt,
                    originalInvestigationPrompt,
                    proposalPrompt,
                    proposalPrompt,
                    "record_confirmed_terms_proposal"
            );
        }

        private RepairLoopState toProposalRepairPrompt(String proposalRepairPrompt) {
            return new RepairLoopState(
                    RepairStage.RECORD_CONFIRMED_TERMS_PROPOSAL,
                    systemPrompt,
                    originalInvestigationPrompt,
                    originalProposalPrompt,
                    proposalRepairPrompt,
                    "record_confirmed_terms_proposal_repair"
            );
        }
    }

    private record LoopOutcome(ReviewToolDecision finalDecision,
                               RepairLoopState nextState,
                               int repairsUsed) {

        private static LoopOutcome finalDecision(ReviewToolDecision decision, int repairsUsed) {
            return new LoopOutcome(decision, null, repairsUsed);
        }

        private static LoopOutcome repair(RepairLoopState nextState, int repairsUsed) {
            return new LoopOutcome(null, nextState, repairsUsed);
        }
    }
}
