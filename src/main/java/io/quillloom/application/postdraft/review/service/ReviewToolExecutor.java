package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.EvidenceSufficiency;
import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectIssueBacklog;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewBoundaryWindow;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewGuardrailRejection;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolCall;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolExecutionResult;
import io.quillloom.application.postdraft.review.model.ReviewToolTrace;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.model.ToolCallSignature;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentTermWriter;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.shared.TermTextNormalizer;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationDecisionNote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class ReviewToolExecutor {

    private static final int NO_PROGRESS_REJECTION_THRESHOLD = 3;

    private final ReviewToolRegistry registry;
    private final ReviewToolGuardrail guardrail;
    private final PostDraftReviewAgentReader reader;
    private final PostDraftReviewAgentTermWriter termWriter;
    private final PromptBackedStrategyEvaluationService evaluationService;
    private final PostDraftRevisionService revisionService;
    private final WorkingSetCompletionHandler completionHandler;
    private final PostDraftReviewProcessSummaryAssembler summaryAssembler;
    private final FocusHumanStopPolicy stopPolicy;

    public ReviewToolExecutor(ReviewToolRegistry registry,
                              ReviewToolGuardrail guardrail,
                              PostDraftReviewAgentReader reader,
                              PostDraftReviewAgentTermWriter termWriter,
                              PromptBackedStrategyEvaluationService evaluationService,
                              PostDraftRevisionService revisionService,
                              WorkingSetCompletionHandler completionHandler,
                              PostDraftReviewProcessSummaryAssembler summaryAssembler,
                              FocusHumanStopPolicy stopPolicy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.guardrail = Objects.requireNonNull(guardrail, "guardrail");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.termWriter = Objects.requireNonNull(termWriter, "termWriter");
        this.evaluationService = Objects.requireNonNull(evaluationService, "evaluationService");
        this.revisionService = Objects.requireNonNull(revisionService, "revisionService");
        this.completionHandler = Objects.requireNonNull(completionHandler, "completionHandler");
        this.summaryAssembler = Objects.requireNonNull(summaryAssembler, "summaryAssembler");
        this.stopPolicy = Objects.requireNonNull(stopPolicy, "stopPolicy");
    }

    public ReviewToolExecutionResult execute(ProjectReviewRuntimeSession runtime,
                                             ReviewToolDecision decision) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(decision, "decision");

        ReviewToolCall call = decision.toCall();
        ReviewGuardrailRejection rejection = guardrail.validate(call, registry);
        if (rejection.rejected()) {
            return ReviewToolExecutionResult.rejected(call, appendAudit(runtime, call, rejection.rejectionReason()), rejection);
        }

        return switch (call.toolName()) {
            case "read_previous_chunks" -> executeReadAdjacent(runtime, call, true);
            case "read_next_chunks" -> executeReadAdjacent(runtime, call, false);
            case "expand_block_context" -> executeExpandBlock(runtime, call);
            case "read_decision_notes" -> executeReadDecisionNotes(runtime, call);
            case "read_transition_note" -> executeReadTransition(runtime, call);
            case "lookup_knowledge_cards" -> executeKnowledgeLookup(runtime, call);
            case "read_confirmed_terms" -> executeReadConfirmedTerms(runtime, call);
            case "record_confirmed_terms" -> executeRecordConfirmedTerms(runtime, call);
            case "evaluate_focus" -> executeEvaluate(runtime, call);
            case "draft_revision" -> executeDraftRevision(runtime, call);
            case "request_human_review" -> executeHumanReview(runtime, call);
            case "complete_working_set" -> executeCompleteWorkingSet(runtime, call);
            case "complete_project" -> executeCompleteProject(runtime, call);
            default -> ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, "unsupported_tool"),
                    ReviewGuardrailRejection.rejected(call.toolName(), "unsupported_tool")
            );
        };
    }

    private ReviewToolExecutionResult executeReadAdjacent(ProjectReviewRuntimeSession runtime,
                                                          ReviewToolCall call,
                                                          boolean previous) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        int count = requirePositiveCount(call.arguments().get("count"));
        int before = previous ? count : 0;
        int after = previous ? 0 : count;
        String boundaryChunkId = previous
                ? session.boundaryWindow().leftEdgeChunkId().orElse(session.focus().chunkId())
                : session.boundaryWindow().rightEdgeChunkId().orElse(session.focus().chunkId());
        List<PostDraftChunkRecord> chunks = reader.readAdjacentChunks(
                runtime.projectId(),
                boundaryChunkId,
                before,
                after
        );
        return applyReadChunks(runtime, call, chunks, previous ? "read_previous_chunks" : "read_next_chunks");
    }

    private ReviewToolExecutionResult executeExpandBlock(ProjectReviewRuntimeSession runtime,
                                                         ReviewToolCall call) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        List<PostDraftChunkRecord> chunks = reader.expandByBlock(runtime.projectId(), session.focus().chunkId());
        return applyReadChunks(runtime, call, chunks, "expand_block_context");
    }

    private ReviewToolExecutionResult applyReadChunks(ProjectReviewRuntimeSession runtime,
                                                      ReviewToolCall call,
                                                      List<PostDraftChunkRecord> chunks,
                                                      String traceName) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        List<PostDraftChunkRecord> safeChunks = chunks == null ? List.of() : List.copyOf(chunks);
        LinkedHashSet<String> currentChunkIds = new LinkedHashSet<>(session.workingSet().chunkIds());
        LinkedHashSet<String> nextChunkIds = new LinkedHashSet<>(session.workingSet().chunkIds());
        LinkedHashMap<String, ReviewContextChunkSnapshot> boundarySnapshots = new LinkedHashMap<>();
        for (ReviewContextChunkSnapshot snapshot : session.boundaryWindow().snapshots()) {
            boundarySnapshots.put(snapshot.chunkId(), snapshot);
        }
        ArrayList<ReviewContextChunkSnapshot> workingSetSnapshots = new ArrayList<>();
        LinkedHashSet<String> readChunkIds = new LinkedHashSet<>(session.readInFocusChunkIds());
        ArrayList<String> readContext = new ArrayList<>();
        ArrayList<String> evidence = new ArrayList<>();
        for (PostDraftChunkRecord chunk : safeChunks) {
            if (chunk == null) {
                continue;
            }
            nextChunkIds.add(chunk.chunkId());
            readChunkIds.add(chunk.chunkId());
            ReviewContextChunkSnapshot snapshot = ReviewChunkSnapshotFormatter.toContextSnapshot(
                    chunk,
                    session.focus().chunkId().equals(chunk.chunkId())
            );
            boundarySnapshots.put(chunk.chunkId(), snapshot);
            workingSetSnapshots.add(snapshot);
            String summary = summarizeChunk(chunk);
            readContext.add(summary);
            evidence.add(summary);
        }
        if (nextChunkIds.equals(currentChunkIds)) {
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, "redundant_adjacent_read"),
                    ReviewGuardrailRejection.rejected(call.toolName(), "redundant_adjacent_read")
            );
        }
        PostDraftReviewSession updatedSession = session
                .withWorkingSet(session.workingSet().expandTo(List.copyOf(nextChunkIds)))
                .withWorkingSetContext(session.workingSetContext().merge(workingSetSnapshots))
                .withBoundaryWindow(new ReviewBoundaryWindow(new ArrayList<>(boundarySnapshots.values())))
                .markChunksReadInFocus(Set.copyOf(readChunkIds))
                .withEvidenceBundle(session.evidenceBundle().merge(new ReviewEvidenceBundle(
                        readContext,
                        evidence,
                        List.of(),
                        List.of(),
                        List.of()
                )))
                .appendToolTrace(new ReviewToolTrace(traceName, call.reason(), readContext))
                .appendTranscript(traceName + " -> " + readContext.size() + " chunk(s)")
                .appendHistory(traceName, String.join(" | ", readContext))
                .withAutonomyState(session.autonomyState().afterInvestigationTurn().clearLocalFailures());
        ProjectReviewRuntimeSession nextRuntime = runtime.withInvestigatingFocusSession(
                updatedSession,
                runtime.currentFocusRound() + 1
        );
        return ReviewToolExecutionResult.success(call, nextRuntime, traceName);
    }

    private ReviewToolExecutionResult executeReadDecisionNotes(ProjectReviewRuntimeSession runtime,
                                                               ReviewToolCall call) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        List<TranslationDecisionNote> notes = reader.readDecisionNotes(runtime.projectId(), session.focus().chunkId());
        ArrayList<String> summaries = new ArrayList<>();
        for (TranslationDecisionNote note : notes) {
            if (note != null) {
                summaries.add("decision=" + safe(note.type()) + ":" + safe(note.description()));
            }
        }
        return applyEvidence(runtime, call, "read_decision_notes", summaries, List.of());
    }

    private ReviewToolExecutionResult executeReadTransition(ProjectReviewRuntimeSession runtime,
                                                            ReviewToolCall call) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        Optional<ChunkTransitionNote> transitionNote = reader.readTransitionNote(runtime.projectId(), session.focus().chunkId());
        List<String> summaries = transitionNote
                .map(note -> List.of("transition=" + safe(note.nextChunkConnection())))
                .orElseGet(List::of);
        return applyEvidence(runtime, call, "read_transition_note", summaries, List.of());
    }

    private ReviewToolExecutionResult executeKnowledgeLookup(ProjectReviewRuntimeSession runtime,
                                                             ReviewToolCall call) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        List<String> queryTerms = toStringListFromArgument(call.arguments().get("queryTerms"), "queryTerms");
        List<KnowledgeCard> cards = reader.lookupKnowledgeCards(runtime.projectId(), session.focus().chunkId(), queryTerms);
        ArrayList<String> summaries = new ArrayList<>();
        for (KnowledgeCard card : cards) {
            if (card != null) {
                summaries.add("knowledgeCard=" + safe(card.cardId()) + ":" + safe(card.title()));
            }
        }
        return applyEvidence(runtime, call, "lookup_knowledge_cards", summaries, List.of());
    }

    private ReviewToolExecutionResult executeReadConfirmedTerms(ProjectReviewRuntimeSession runtime,
                                                                ReviewToolCall call) {
        List<String> sourceTerms = toStringListFromArgument(call.arguments().get("sourceTerms"), "sourceTerms");
        ToolCallSignature signature = ToolCallSignature.forReadConfirmedTerms(sourceTerms);
        PostDraftReviewSession session = requireFocusSession(runtime);
        if (hasSuccessfulToolCall(session, signature)) {
            String detail = "redundant_successful_tool_call:" + signature.key();
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, detail),
                    ReviewGuardrailRejection.rejected(call.toolName(), detail)
            );
        }
        Set<String> requestedSourceKeys = normalizedSourceKeys(sourceTerms);
        Set<String> repeatedSourceKeys = new LinkedHashSet<>(successfulReadConfirmedTermKeys(session));
        repeatedSourceKeys.retainAll(requestedSourceKeys);
        if (!repeatedSourceKeys.isEmpty()) {
            String detail = "redundant_successful_term_lookup:sourceTerms=[" + String.join(", ", repeatedSourceKeys) + "]";
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, detail),
                    ReviewGuardrailRejection.rejected(call.toolName(), detail)
            );
        }
        Map<String, String> confirmedTerms = reader.readConfirmedTerms(runtime.projectId(), sourceTerms);
        ArrayList<String> summaries = new ArrayList<>();
        ArrayList<String> misses = new ArrayList<>();
        for (String sourceTerm : sourceTerms) {
            String targetTerm = confirmedTerms.get(sourceTerm);
            if (targetTerm != null && !targetTerm.isBlank()) {
                summaries.add("confirmedTerm=" + sourceTerm + "->" + targetTerm);
            } else {
                misses.add(sourceTerm);
            }
        }
        if (!misses.isEmpty()) {
            summaries.add("confirmedTermLookupMiss=" + misses);
        }
        return applyReadConfirmedTermsEvidence(runtime, call, signature, summaries);
    }

    private boolean hasSuccessfulToolCall(PostDraftReviewSession session, ToolCallSignature signature) {
        return session.toolTraces().stream()
                .anyMatch(trace -> signature.toolName().equals(trace.toolName())
                        && signature.key().equals(trace.callSignature()));
    }

    private Set<String> normalizedSourceKeys(List<String> sourceTerms) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String sourceTerm : Objects.requireNonNullElse(sourceTerms, List.<String>of())) {
            String key = TermTextNormalizer.keyText(sourceTerm);
            if (!key.isBlank()) {
                keys.add(key);
            }
        }
        return Set.copyOf(keys);
    }

    private Set<String> successfulReadConfirmedTermKeys(PostDraftReviewSession session) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (ReviewToolTrace trace : session.toolTraces()) {
            if (!"read_confirmed_terms".equals(trace.toolName())) {
                continue;
            }
            keys.addAll(readConfirmedTermKeysFromSignature(trace.callSignature()));
        }
        return Set.copyOf(keys);
    }

    private Set<String> lookupMissKeys(PostDraftReviewSession session) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (ReviewToolTrace trace : session.toolTraces()) {
            if (!"read_confirmed_terms".equals(trace.toolName())) {
                continue;
            }
            for (String note : trace.notes()) {
                if (note == null || !note.startsWith("confirmedTermLookupMiss=")) {
                    continue;
                }
                keys.addAll(normalizedSourceKeys(parseBracketedList(note.substring("confirmedTermLookupMiss=".length()))));
            }
        }
        return Set.copyOf(keys);
    }

    private Set<String> readConfirmedTermKeysFromSignature(String callSignature) {
        if (callSignature == null || !callSignature.startsWith("read_confirmed_terms:sourceTerms=[")) {
            return Set.of();
        }
        int start = callSignature.indexOf('[');
        int end = callSignature.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return Set.of();
        }
        return normalizedSourceKeys(parseBracketedList(callSignature.substring(start, end + 1)));
    }

    private ReviewToolExecutionResult executeRecordConfirmedTerms(ProjectReviewRuntimeSession runtime,
                                                                  ReviewToolCall call) {
        Map<String, String> entries = toStringMapFromArgument(call.arguments().get("entries"), "entries");
        Map<String, String> existingConfirmedTerms = reader.readConfirmedTerms(runtime.projectId(), List.copyOf(entries.keySet()));
        if (!existingConfirmedTerms.isEmpty()) {
            String detail = "invalid_record_confirmed_terms_basis:already_registered_in_project_confirmed_terms";
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, detail),
                    ReviewGuardrailRejection.rejected(call.toolName(), detail)
            );
        }
        Map<String, String> recordedEntries;
        try {
            recordedEntries = termWriter.recordConfirmedTerms(runtime.projectId(), entries);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            String detail = safe(ex.getMessage());
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, detail),
                    ReviewGuardrailRejection.rejected(call.toolName(), detail)
            );
        }
        ArrayList<String> summaries = new ArrayList<>();
        recordedEntries.forEach((sourceTerm, targetTerm) ->
                summaries.add("recordedConfirmedTerm=" + sourceTerm + "->" + targetTerm));
        return applyEvidence(runtime, call, "record_confirmed_terms", summaries, List.of());
    }

    private ReviewToolExecutionResult applyEvidence(ProjectReviewRuntimeSession runtime,
                                                    ReviewToolCall call,
                                                    String traceName,
                                                    List<String> evidenceSummaries,
                                                    List<String> evidenceGaps) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        PostDraftReviewSession updatedSession = session
                .withEvidenceBundle(session.evidenceBundle().merge(new ReviewEvidenceBundle(
                        List.of(),
                        evidenceSummaries,
                        evidenceSummaries,
                        List.of(),
                        evidenceGaps
                )))
                .appendToolTrace(new ReviewToolTrace(traceName, call.reason(), evidenceSummaries))
                .appendTranscript(traceName + " -> " + evidenceSummaries.size() + " item(s)")
                .appendHistory(traceName, String.join(" | ", evidenceSummaries))
                .withAutonomyState(session.autonomyState().afterInvestigationTurn().clearLocalFailures());
        ProjectReviewRuntimeSession nextRuntime = runtime.withInvestigatingFocusSession(
                updatedSession,
                runtime.currentFocusRound() + 1
        );
        return ReviewToolExecutionResult.success(call, nextRuntime, traceName);
    }

    private ReviewToolExecutionResult applyReadConfirmedTermsEvidence(ProjectReviewRuntimeSession runtime,
                                                                      ReviewToolCall call,
                                                                      ToolCallSignature signature,
                                                                      List<String> evidenceSummaries) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        String toolUse = ReviewToolMemoryFormatter.renderReadConfirmedTermsUse(signature);
        String toolResult = ReviewToolMemoryFormatter.renderToolResult(signature, evidenceSummaries);
        PostDraftReviewSession updatedSession = session
                .withEvidenceBundle(session.evidenceBundle().merge(new ReviewEvidenceBundle(
                        List.of(),
                        evidenceSummaries,
                        evidenceSummaries,
                        List.of(),
                        List.of()
                )))
                .appendToolTrace(new ReviewToolTrace("read_confirmed_terms", call.reason(), evidenceSummaries, signature.key()))
                .appendTranscript(toolUse)
                .appendTranscript(toolResult)
                .appendHistory("read_confirmed_terms", toolUse)
                .appendHistory("read_confirmed_terms", toolResult)
                .withAutonomyState(session.autonomyState().afterInvestigationTurn().clearLocalFailures());
        ProjectReviewRuntimeSession nextRuntime = runtime.withInvestigatingFocusSession(
                updatedSession,
                runtime.currentFocusRound() + 1
        );
        return ReviewToolExecutionResult.success(call, nextRuntime, toolResult);
    }

    private ReviewToolExecutionResult executeEvaluate(ProjectReviewRuntimeSession runtime,
                                                      ReviewToolCall call) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        PostDraftChunkRecord chunk = requireCurrentChunk(runtime);
        ReviewAgentEvaluation evaluation = evaluationService.evaluate(session, chunk);

        ReviewEvidenceBundle nextEvidence = session.evidenceBundle().merge(new ReviewEvidenceBundle(
                List.of(),
                List.of("evaluation=" + evaluation.strategyReason()),
                evaluation.evidenceSufficiency() == EvidenceSufficiency.SUFFICIENT
                        ? List.of(evaluation.strategyReason())
                        : List.of(),
                evaluation.evidenceSufficiency() == EvidenceSufficiency.INSUFFICIENT
                        ? List.of(evaluation.strategyReason())
                        : List.of(),
                List.of()
        ));
        PostDraftReviewSession updatedSession = session
                .withEvidenceBundle(nextEvidence)
                .withStrategy(evaluation.recommendedStrategy())
                .appendToolTrace(new ReviewToolTrace("evaluate_focus", call.reason(), List.of(
                        "recommendedStrategy=" + evaluation.recommendedStrategy(),
                        "evidenceSufficiency=" + evaluation.evidenceSufficiency(),
                        "continueInvestigation=" + evaluation.continueInvestigation()
                )))
                .appendTranscript("evaluate_focus -> " + evaluation.recommendedStrategy())
                .appendHistory("evaluate_focus", evaluation.strategyReason())
                .withAutonomyState(session.autonomyState().clearLocalFailures());

        if (evaluation.recommendedStrategy() == ReviewStrategy.REQUIRE_HUMAN_REVIEW) {
            HumanReviewRequest request = buildHumanRequest(runtime, updatedSession, chunk, call.reason(), "evaluation_requires_human");
            ProjectReviewRuntimeSession nextRuntime = runtime.withInvestigatingFocusSession(
                    updatedSession.withWaitingHumanState(),
                    runtime.currentFocusRound()
            ).withHumanReviewRequest(request);
            return ReviewToolExecutionResult.success(call, nextRuntime, "request_human_review");
        }

        PostDraftReviewSession nextSession = evaluation.continueInvestigation()
                ? updatedSession.withInvestigatingState()
                : updatedSession.withEvaluatingState();
        ProjectReviewRuntimeSession nextRuntime = runtime.withInvestigatingFocusSession(
                nextSession,
                runtime.currentFocusRound()
        );
        return ReviewToolExecutionResult.success(call, nextRuntime, "evaluate_focus");
    }

    private ReviewToolExecutionResult executeDraftRevision(ProjectReviewRuntimeSession runtime,
                                                           ReviewToolCall call) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        if (hasLowPriorityRuntimeSignals(session) && lacksHighRiskActionRuntimeBasis(runtime, session)) {
            String detail = "invalid_high_risk_action_basis:draft_revision:low_priority_signals_only";
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, detail),
                    ReviewGuardrailRejection.rejected(call.toolName(), detail)
            );
        }
        if (!isExecutableRevisionStrategy(session.strategy())) {
            String detail = "invalid_strategy_for_tool:draft_revision:" + session.strategy();
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, detail),
                    ReviewGuardrailRejection.rejected(call.toolName(), detail)
            );
        }
        if (hasSuccessfulRevisionTrace(session.toolTraces())) {
            String detail = "redundant_tool_call:draft_revision_after_successful_self_check";
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, detail),
                    ReviewGuardrailRejection.rejected(call.toolName(), detail)
            );
        }
        PostDraftChunkRecord chunk = requireCurrentChunk(runtime);
        RevisionDraft draft;
        try {
            draft = revisionService.generate(session, chunk, session.strategy());
        } catch (IllegalStateException | IllegalArgumentException ex) {
            String detail = "revision_draft_generation_failed:" + safe(ex.getMessage());
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, detail),
                    ReviewGuardrailRejection.rejected(call.toolName(), detail)
            );
        }
        RevisionSelfCheckResult selfCheck = revisionService.selfCheck(session, chunk, session.strategy(), draft);

        PostDraftReviewSession revisedSession = session
                .appendToolTrace(new ReviewToolTrace("draft_revision", call.reason(), List.of(
                        "finalTranslation=" + draft.formalTranslation(),
                        "revisionMode=" + draft.revisionMode(),
                        "selfCheckPassed=" + selfCheck.passed()
                )))
                .appendTranscript("draft_revision -> " + draft.revisionMode())
                .appendHistory("draft_revision", "passed=" + selfCheck.passed())
                .withAutonomyState(session.autonomyState().afterRevisionAttempt().clearLocalFailures());

        if (!selfCheck.passed()) {
            PostDraftReviewSession failedSession = revisedSession.withAutonomyState(
                    revisedSession.autonomyState().afterSelfCheckFailure(selfCheck.findings())
            );
            if (stopPolicy.shouldEscalate(failedSession, selfCheck)) {
                HumanReviewRequest request = buildHumanRequest(
                        runtime,
                        failedSession.withWaitingHumanState(),
                        chunk,
                        selfCheck.stopReason(),
                        "revision_self_check_failed"
                );
                ProjectReviewRuntimeSession waitingRuntime = runtime.withInvestigatingFocusSession(
                        failedSession.withWaitingHumanState(),
                        runtime.currentFocusRound()
                ).withHumanReviewRequest(request);
                return ReviewToolExecutionResult.success(call, waitingRuntime, "request_human_review");
            }
        }

        ProjectReviewRuntimeSession nextRuntime = runtime.withInvestigatingFocusSession(
                revisedSession
                        .appendTranscript("revision_ready_for_completion -> complete_working_set_or_request_human_review")
                        .appendHistory("revision_ready_for_completion", "selfCheckPassed=true")
                        .withEvaluatingState(),
                runtime.currentFocusRound()
        );
        return ReviewToolExecutionResult.success(call, nextRuntime, "draft_revision");
    }

    private ReviewToolExecutionResult executeHumanReview(ProjectReviewRuntimeSession runtime,
                                                         ReviewToolCall call) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        if (hasLowPriorityRuntimeSignals(session) && lacksHighRiskActionRuntimeBasis(runtime, session)) {
            String detail = "invalid_high_risk_action_basis:request_human_review:low_priority_signals_only";
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, detail),
                    ReviewGuardrailRejection.rejected(call.toolName(), detail)
            );
        }
        PostDraftChunkRecord chunk = requireCurrentChunk(runtime);
        HumanReviewRequest request = buildHumanRequest(runtime, session.withWaitingHumanState(), chunk, call.reason(), "project_waiting_human");
        ProjectReviewRuntimeSession nextRuntime = runtime.withInvestigatingFocusSession(
                session.withWaitingHumanState(),
                runtime.currentFocusRound()
        ).withHumanReviewRequest(request);
        return ReviewToolExecutionResult.success(call, nextRuntime, "request_human_review");
    }

    private ReviewToolExecutionResult executeCompleteWorkingSet(ProjectReviewRuntimeSession runtime,
                                                                ReviewToolCall call) {
        PostDraftReviewSession session = requireFocusSession(runtime);
        List<String> chunkIds = toStringList(call.arguments().get("chunkIds"));
        Map<String, String> explicitFinalTranslations = toStringMap(call.arguments().get("finalTranslations"));
        List<ProjectChunkReviewOutcome> outcomes;
        try {
            outcomes = completionHandler.complete(runtime, chunkIds, explicitFinalTranslations);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            String detail = safe(ex.getMessage());
            return ReviewToolExecutionResult.rejected(
                    call,
                    appendAudit(runtime, call, detail),
                    ReviewGuardrailRejection.rejected(call.toolName(), detail)
            );
        }
        ProjectReviewRuntimeSession nextRuntime = runtime.completeWorkingSet(outcomes)
                .withTranscriptStore(runtime.transcriptStore().append("complete_working_set -> " + chunkIds))
                .withHistoryLog(runtime.historyLog().add("complete_working_set", String.join(",", chunkIds)));
        if (nextRuntime.pendingChunkIds().isEmpty()) {
            PostDraftReviewSession completionReadySession = session
                    .appendTranscript("project_ready_for_completion -> call complete_project")
                    .appendHistory("project_ready_for_completion", "pendingChunkCount=0")
                    .withEvaluatingState();
            nextRuntime = nextRuntime.withCurrentFocusSession(
                    completionReadySession,
                    runtime.currentFocusRound(),
                    completionReadySession.state()
            );
        }
        return ReviewToolExecutionResult.success(call, nextRuntime, "complete_working_set");
    }

    private ReviewToolExecutionResult executeCompleteProject(ProjectReviewRuntimeSession runtime,
                                                             ReviewToolCall call) {
        ProjectReviewRuntimeSession nextRuntime = runtime.completeProject();
        return ReviewToolExecutionResult.success(call, nextRuntime, "complete_project");
    }

    private ProjectReviewRuntimeSession appendAudit(ProjectReviewRuntimeSession runtime,
                                                    ReviewToolCall call,
                                                    String detail) {
        ProjectReviewRuntimeSession projectUpdated = runtime.withTranscriptStore(
                        runtime.transcriptStore().append("rejected " + call.toolName() + " -> " + detail)
                )
                .withHistoryLog(runtime.historyLog().add(call.toolName(), detail));
        if (projectUpdated.currentFocusSession().isEmpty()) {
            return projectUpdated;
        }

        PostDraftReviewSession session = projectUpdated.currentFocusSession().orElseThrow();
        String rejectionKey = "guardrail:" + call.toolName() + ":" + detail;
        PostDraftReviewSession updatedSession = session
                .appendTranscript("rejected " + call.toolName() + " -> " + detail)
                .appendHistory(call.toolName(), detail)
                .withAutonomyState(session.autonomyState().afterLocalFailure(rejectionKey));
        String correctionHint = buildLocalCorrectionHint(updatedSession, call, detail);
        if (!correctionHint.isBlank()) {
            updatedSession = updatedSession
                    .appendTranscript(correctionHint)
                    .appendHistory("local_replan_hint", correctionHint);
        }
        ProjectReviewRuntimeSession updatedRuntime = projectUpdated.withCurrentFocusSession(
                updatedSession,
                projectUpdated.currentFocusRound(),
                updatedSession.state()
        );
        if (updatedSession.diagnostics().consecutiveLocalFailureCount(rejectionKey) >= NO_PROGRESS_REJECTION_THRESHOLD) {
            PostDraftReviewSession failedSession = updatedSession
                    .appendTranscript("stop_reason -> no_progress:" + rejectionKey)
                    .appendHistory("stop_reason", "no_progress:" + rejectionKey);
            return updatedRuntime.withCurrentFocusSession(
                    failedSession,
                    updatedRuntime.currentFocusRound(),
                    failedSession.state()
            ).failNoProgress(rejectionKey);
        }
        return updatedRuntime;
    }

    private String buildLocalCorrectionHint(PostDraftReviewSession session,
                                            ReviewToolCall call,
                                            String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        if (detail.startsWith("missing_argument:")) {
            return "local_replan_hint -> " + call.toolName()
                    + " is missing required arguments. Retry only after supplying requiredArguments="
                    + registry.require(call.toolName()).requiredArguments()
                    + ".";
        }
        if ("redundant_adjacent_read".equals(detail)) {
            String otherDirection = "read_previous_chunks".equals(call.toolName())
                    ? "read_next_chunks"
                    : "read_previous_chunks";
            return "local_replan_hint -> the current adjacent-read direction produced no net-new chunk. "
                    + "Do not repeat the same direction. If more context is still needed, try "
                    + otherDirection
                    + "; otherwise prefer evaluate_focus or complete_working_set when evidence is already sufficient.";
        }
        if (detail.startsWith("complete_working_set chunkIds must include anchorChunkId=")) {
            return "local_replan_hint -> complete_working_set.chunkIds must include the current anchor="
                    + session.focus().chunkId()
                    + ". Any additional chunk must also come from the current workingSet="
                    + session.workingSet().chunkIds()
                    + ".";
        }
        if (detail.startsWith("complete_working_set chunkIds must stay within currentWorkingSet=")) {
            return "local_replan_hint -> complete_working_set may submit only chunks from the current workingSet="
                    + session.workingSet().chunkIds()
                    + ". Do not submit chunks that were not read or fixed in this round.";
        }
        if (detail.startsWith("complete_working_set chunkIds must still be pending")) {
            return "local_replan_hint -> complete_working_set may submit only chunks that are still pending. "
                    + "Already completed chunks may stay in workingSet as context evidence, but they must not be submitted again. "
                    + "Remove completed chunks from chunkIds before retrying.";
        }
        if ("redundant_tool_call:draft_revision_after_successful_self_check".equals(detail)) {
            return "local_replan_hint -> draft_revision already succeeded and self-check already passed. Prefer complete_working_set. "
                    + "chunkIds must include the current anchor="
                    + session.focus().chunkId()
                    + " and may only be selected from the current workingSet="
                    + session.workingSet().chunkIds()
                    + ".";
        }
        if (detail.startsWith("redundant_successful_tool_call:")) {
            return "local_replan_hint -> the same read_confirmed_terms signature already succeeded: " + detail
                    + ". Do not repeat that lookup. If you want to query different terms, update arguments.sourceTerms accordingly "
                    + "and keep only source terms that have not been queried yet. If current evidence is already enough, use complete_working_set; "
                    + "if a real issue is found, use evaluate_focus; if local tools still cannot resolve it, then use request_human_review.";
        }
        if (detail.startsWith("redundant_successful_term_lookup:")) {
            List<String> requestedSourceTerms = toStringListFromArgument(call.arguments().get("sourceTerms"), "sourceTerms");
            Set<String> repeatedKeys = readConfirmedTermKeysFromDetail(detail);
            List<String> remainingSourceTerms = requestedSourceTerms.stream()
                    .filter(sourceTerm -> !repeatedKeys.contains(TermTextNormalizer.keyText(sourceTerm)))
                    .toList();
            return "local_replan_hint -> read_confirmed_terms contains sourceTerms that were already looked up successfully or already confirmed as misses: "
                    + detail.substring("redundant_successful_term_lookup:".length())
                    + ". If you still need another lookup, remove already-checked items from arguments.sourceTerms."
                    + (remainingSourceTerms.isEmpty()
                    ? " No newly in-scope sourceTerm remains in this call."
                    : " Keep only sourceTerms=" + remainingSourceTerms + " because they are the only newly in-scope sourceTerm values in this call.")
                    + "If evidence is already enough, use complete_working_set; if issues remain, use evaluate_focus.";
        }
        if (detail.startsWith("invalid_record_confirmed_terms_basis:")) {
            return "local_replan_hint -> record_confirmed_terms failed because the project-level confirmed-term table already contains the requested source term, "
                    + "or because arguments.entries is invalid. Do not retry the same registration. "
                    + "If the current translation is already acceptable, prefer complete_working_set. "
                    + "If a real translation issue exists, use evaluate_focus.";
        }
        if (detail.startsWith("invalid_high_risk_action_basis:")) {
            return "local_replan_hint -> decisionNotes, translatorCommentary, transitionNote, and confirmedTermLookupMiss are low-priority signals. "
                    + "They may support continued investigation or evaluate_focus, but they may not independently trigger a high-risk action. "
                    + "Strengthen sourceText / translatedText / tool-result evidence first, or switch to evaluate_focus.";
        }
        if (detail.startsWith("invalid_strategy_for_tool:draft_revision")) {
            return "local_replan_hint -> the current strategy does not allow draft_revision. Re-run evaluate_focus or gather more evidence first. "
                    + "draft_revision is allowed only after the strategy becomes LIGHT_EDIT / DEEP_EDIT / RETRANSLATE.";
        }
        if (detail.startsWith("revision_draft_generation_failed:")) {
            return "local_replan_hint -> revision draft generation failed. This is a local correction signal, not a human-review signal. "
                    + "Adjust context or replan the next step first; do not escalate to request_human_review only because this draft failed.";
        }
        return "local_replan_hint -> the current call was rejected. Replan locally and prefer fixing arguments or selecting a more appropriate tool.";
    }

    private PostDraftReviewSession requireFocusSession(ProjectReviewRuntimeSession runtime) {
        return runtime.currentFocusSession()
                .orElseThrow(() -> new IllegalStateException("tool execution requires currentFocusSession"));
    }

    private PostDraftChunkRecord requireCurrentChunk(ProjectReviewRuntimeSession runtime) {
        String chunkId = runtime.currentFocusChunkId()
                .orElseThrow(() -> new IllegalStateException("tool execution requires currentFocusChunkId"));
        return reader.loadChunkById(runtime.projectId(), chunkId)
                .orElseThrow(() -> new IllegalStateException("Chunk not found: " + chunkId));
    }

    private int requirePositiveCount(Object value) {
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        throw new IllegalArgumentException("count must be a positive number");
    }

    private List<String> toStringList(Object value) {
        return toStringListFromArgument(value, "chunkIds");
    }

    private Map<String, String> toStringMap(Object value) {
        return toStringMapFromArgument(value, "finalTranslations");
    }

    private List<String> toStringListFromArgument(Object value, String argumentName) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String stringValue) {
            if (stringValue.isBlank()) {
                return List.of();
            }
            if (stringValue.startsWith("[")) {
                return parseCommaSeparated(stringValue.substring(1, stringValue.length() - 1));
            }
            return List.of(stringValue.trim());
        }
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        ArrayList<String> normalized = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof String s && !s.isBlank()) {
                normalized.add(s.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private Set<String> readConfirmedTermKeysFromDetail(String detail) {
        if (detail == null || !detail.startsWith("redundant_successful_term_lookup:sourceTerms=[")) {
            return Set.of();
        }
        int start = detail.indexOf('[');
        int end = detail.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return Set.of();
        }
        return normalizedSourceKeys(parseBracketedList(detail.substring(start, end + 1)));
    }

    private List<String> parseCommaSeparated(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        ArrayList<String> result = new ArrayList<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim().replaceAll("^\"|\"$", "");
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    private List<String> parseBracketedList(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return parseCommaSeparated(normalized);
    }

    private boolean lacksHighRiskActionRuntimeBasis(ProjectReviewRuntimeSession runtime,
                                                    PostDraftReviewSession session) {
        if (!currentWorkingSetConfirmedTermUpdates(runtime, session).isEmpty()) {
            return false;
        }
        if (!session.evidenceBundle().conflictingEvidenceSummaries().isEmpty()) {
            return false;
        }
        for (String evidence : session.evidenceSummaries()) {
            String text = safe(evidence);
            if (text.startsWith("confirmedTerm=")
                    || text.startsWith("recordedConfirmedTerm=")
                    || text.startsWith("knowledgeCard=")
                    || text.startsWith("contextChunk=")
                    || text.startsWith("evaluation=")) {
                return false;
            }
        }
        for (ReviewToolTrace trace : session.toolTraces()) {
            String toolName = trace.toolName();
            if ("read_previous_chunks".equals(toolName)
                    || "read_next_chunks".equals(toolName)
                    || "expand_block_context".equals(toolName)
                    || "lookup_knowledge_cards".equals(toolName)
                    || "read_confirmed_terms".equals(toolName)
                    || "record_confirmed_terms".equals(toolName)
                    || "evaluate_focus".equals(toolName)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasLowPriorityRuntimeSignals(PostDraftReviewSession session) {
        for (String evidence : session.evidenceSummaries()) {
            String text = safe(evidence);
            if (text.startsWith("decision=")
                    || text.startsWith("transition=")
                    || text.startsWith("confirmedTermLookupMiss=")
                    || text.startsWith("translatorCommentary=")) {
                return true;
            }
        }
        for (ReviewToolTrace trace : session.toolTraces()) {
            if ("read_decision_notes".equals(trace.toolName())
                    || "read_transition_note".equals(trace.toolName())) {
                return true;
            }
            if ("read_confirmed_terms".equals(trace.toolName())
                    && trace.notes().stream().anyMatch(note -> note.startsWith("confirmedTermLookupMiss="))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> currentWorkingSetConfirmedTermUpdates(ProjectReviewRuntimeSession runtime,
                                                                      PostDraftReviewSession session) {
        LinkedHashMap<String, String> supportedEntries = new LinkedHashMap<>();
        for (String chunkId : session.workingSet().chunkIds()) {
            Optional<PostDraftChunkRecord> chunk = reader.loadChunkById(runtime.projectId(), chunkId);
            if (chunk.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> confirmedEntry : chunk.get().confirmedTermUpdates().entrySet()) {
                String sourceKey = TermTextNormalizer.keyText(confirmedEntry.getKey());
                String targetTerm = confirmedEntry.getValue();
                if (sourceKey.isBlank() || targetTerm == null || targetTerm.isBlank()) {
                    continue;
                }
                supportedEntries.putIfAbsent(sourceKey, targetTerm);
            }
        }
        return Map.copyOf(supportedEntries);
    }

    private Map<String, String> toStringMapFromArgument(Object value, String argumentName) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof String stringValue) {
                normalized.put(key, stringValue);
            }
        }
        return Map.copyOf(normalized);
    }

    private HumanReviewRequest buildHumanRequest(ProjectReviewRuntimeSession runtime,
                                                 PostDraftReviewSession session,
                                                 PostDraftChunkRecord chunk,
                                                 String reasonDetail,
                                                 String requestReason) {
        ReviewProcessSummary processSummary = summaryAssembler.assemble(
                session,
                chunk,
                session.strategy(),
                session.problemTypes(),
                session.evidenceSummaries()
        );
        return new HumanReviewRequest(
                runtime.projectId(),
                ReviewFocus.forChunk(chunk.chunkId()),
                processSummary,
                "chunk=" + chunk.chunkId() + ", reason=" + safe(reasonDetail),
                buildQuestionForHuman(chunk, reasonDetail),
                requestReason,
                ReviewAgentState.WAITING_HUMAN,
                "resumeDecision=continue_investigation|enter_revision",
                runtime.completedChunkOutcomes().size(),
                runtime.pendingChunkIds().size()
        );
    }

    private String buildQuestionForHuman(PostDraftChunkRecord chunk, String reasonDetail) {
        String safeReason = safe(reasonDetail).toLowerCase(java.util.Locale.ROOT);
        String sourceText = safe(chunk.sourceText());
        String translatedText = safe(chunk.effectiveTranslatedText());
        String focusLabel = "chunk=" + safe(chunk.chunkId());

        if (looksLikeNamingQuestion(safeReason, sourceText)) {
            return "请确认 " + focusLabel + " 中相关名称/术语的统一译法。原文涉及："
                    + sourceText
                    + "；当前译文："
                    + translatedText
                    + "。如需统一，请给出应采用的译名或术语。";
        }
        if (looksLikeReferenceOrContinuityQuestion(safeReason)) {
            return "请确认 " + focusLabel + " 的衔接或指代关系是否成立。请结合原文、当前译文与相邻上下文，说明应如何理解并给出建议译法。";
        }
        if (looksLikeTranslationChoiceQuestion(safeReason)) {
            return "请确认 " + focusLabel + " 当前译法取舍是否可接受。请结合原文语义、语气和上下文，给出应采用的表达。";
        }
        if (looksLikeBinarySemanticQuestion(safeReason)) {
            return "请确认 " + focusLabel + " 存在的语义判断应如何取舍。请审阅原文、当前译文与相关上下文，并给出明确判断依据。";
        }
        return "当前 " + focusLabel + " 存在无法自动解决的语义判断。请审阅原文、当前译文与相关上下文，并给出应采用的译法或判断依据。";
    }

    private boolean looksLikeNamingQuestion(String safeReason, String sourceText) {
        return safeReason.contains("naming")
                || safeReason.contains("term")
                || safeReason.contains("name")
                || safeReason.contains("译名")
                || safeReason.contains("术语")
                || safeReason.contains("命名")
                || !Objects.requireNonNullElse(sourceText, "").isBlank() && Character.isUpperCase(sourceText.trim().charAt(0));
    }

    private boolean looksLikeReferenceOrContinuityQuestion(String safeReason) {
        return safeReason.contains("continuity")
                || safeReason.contains("reference")
                || safeReason.contains("pronoun")
                || safeReason.contains("handoff")
                || safeReason.contains("衔接")
                || safeReason.contains("指代");
    }

    private boolean looksLikeTranslationChoiceQuestion(String safeReason) {
        return safeReason.contains("translation choice")
                || safeReason.contains("wording")
                || safeReason.contains("tone")
                || safeReason.contains("allow")
                || safeReason.contains("取舍")
                || safeReason.contains("译法");
    }

    private boolean looksLikeBinarySemanticQuestion(String safeReason) {
        return safeReason.contains("semantic")
                || safeReason.contains("meaning")
                || safeReason.contains("ambigu")
                || safeReason.contains("whether")
                || safeReason.contains("语义");
    }

    private String summarizeChunk(PostDraftChunkRecord chunk) {
        return ReviewChunkSnapshotFormatter.renderContextChunk(chunk);
    }

    private boolean isExecutableRevisionStrategy(ReviewStrategy strategy) {
        return strategy == ReviewStrategy.LIGHT_EDIT
                || strategy == ReviewStrategy.DEEP_EDIT
                || strategy == ReviewStrategy.RETRANSLATE;
    }

    private boolean hasSuccessfulRevisionTrace(List<ReviewToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return false;
        }
        ReviewToolTrace latest = traces.get(traces.size() - 1);
        if (!"draft_revision".equals(latest.toolName())) {
            return false;
        }
        return latest.notes().stream()
                .anyMatch(note -> "selfCheckPassed=true".equals(note));
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

