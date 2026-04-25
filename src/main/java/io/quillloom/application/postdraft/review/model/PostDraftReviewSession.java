package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PostDraftReviewSession(
        String projectId,
        ReviewFocus focus,
        ReviewWorkingSet workingSet,
        TranscriptStore transcriptStore,
        HistoryLog historyLog,
        ReviewEvidenceBundle evidenceBundle,
        ReviewWorkingSetContext workingSetContext,
        ReviewBoundaryWindow boundaryWindow,
        Set<String> readInFocusChunkIds,
        Set<String> verifiedInFocusChunkIds,
        ReviewVisitedObjects reviewVisitedObjects,
        List<ReviewToolTrace> toolTraces,
        String operatorNote,
        Set<ReviewProblemType> problemTypes,
        ReviewStrategy strategy,
        FocusReviewDiagnostics diagnostics,
        ProjectIssueBacklog issueBacklog,
        ReviewAgentState observationState,
        boolean waitingForHumanReview
) {

    public PostDraftReviewSession(String projectId,
                                  ReviewFocus focus,
                                  ReviewWorkingSet workingSet,
                                  TranscriptStore transcriptStore,
                                  HistoryLog historyLog,
                                  ReviewEvidenceBundle evidenceBundle,
                                  ReviewVisitedObjects reviewVisitedObjects,
                                  List<ReviewToolTrace> toolTraces,
                                  String operatorNote,
                                  Set<ReviewProblemType> problemTypes,
                                  ReviewStrategy strategy,
                                  FocusReviewDiagnostics diagnostics) {
        this(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                ReviewWorkingSetContext.empty(),
                ReviewBoundaryWindow.empty(),
                Set.of(),
                Set.of(),
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics
        );
    }

    public PostDraftReviewSession(String projectId,
                                  ReviewFocus focus,
                                  ReviewWorkingSet workingSet,
                                  TranscriptStore transcriptStore,
                                  HistoryLog historyLog,
                                  ReviewEvidenceBundle evidenceBundle,
                                  ReviewVisitedObjects reviewVisitedObjects,
                                  List<ReviewToolTrace> toolTraces,
                                  String operatorNote,
                                  Set<ReviewProblemType> problemTypes,
                                  ReviewStrategy strategy,
                                  FocusReviewDiagnostics diagnostics,
                                  ProjectIssueBacklog issueBacklog,
                                  ReviewAgentState observationState,
                                  boolean waitingForHumanReview) {
        this(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                ReviewWorkingSetContext.empty(),
                ReviewBoundaryWindow.empty(),
                Set.of(),
                Set.of(),
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession(String projectId,
                                  ReviewFocus focus,
                                  ReviewWorkingSet workingSet,
                                  TranscriptStore transcriptStore,
                                  HistoryLog historyLog,
                                  ReviewEvidenceBundle evidenceBundle,
                                  ReviewWorkingSetContext workingSetContext,
                                  ReviewVisitedObjects reviewVisitedObjects,
                                  List<ReviewToolTrace> toolTraces,
                                  String operatorNote,
                                  Set<ReviewProblemType> problemTypes,
                                  ReviewStrategy strategy,
                                  FocusReviewDiagnostics diagnostics) {
        this(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                ReviewBoundaryWindow.empty(),
                Set.of(),
                Set.of(),
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics
        );
    }

    public PostDraftReviewSession(String projectId,
                                  ReviewFocus focus,
                                  ReviewWorkingSet workingSet,
                                  TranscriptStore transcriptStore,
                                  HistoryLog historyLog,
                                  ReviewEvidenceBundle evidenceBundle,
                                  ReviewWorkingSetContext workingSetContext,
                                  ReviewBoundaryWindow boundaryWindow,
                                  Set<String> readInFocusChunkIds,
                                  Set<String> verifiedInFocusChunkIds,
                                  ReviewVisitedObjects reviewVisitedObjects,
                                  List<ReviewToolTrace> toolTraces,
                                  String operatorNote,
                                  Set<ReviewProblemType> problemTypes,
                                  ReviewStrategy strategy,
                                  FocusReviewDiagnostics diagnostics) {
        this(
                projectId,
                focus,
                workingSet,
                transcriptStore == null ? TranscriptStore.empty() : transcriptStore,
                historyLog == null ? HistoryLog.empty() : historyLog,
                evidenceBundle == null ? ReviewEvidenceBundle.empty() : evidenceBundle,
                workingSetContext == null ? ReviewWorkingSetContext.empty() : workingSetContext,
                boundaryWindow == null ? ReviewBoundaryWindow.empty() : boundaryWindow,
                readInFocusChunkIds == null ? Set.of() : readInFocusChunkIds,
                verifiedInFocusChunkIds == null ? Set.of() : verifiedInFocusChunkIds,
                reviewVisitedObjects == null ? ReviewVisitedObjects.empty() : reviewVisitedObjects,
                toolTraces == null ? List.of() : toolTraces,
                operatorNote,
                problemTypes == null ? Set.of() : problemTypes,
                strategy == null ? ReviewStrategy.KEEP : strategy,
                diagnostics == null ? FocusReviewDiagnostics.empty() : diagnostics,
                ProjectIssueBacklog.empty(),
                ReviewAgentState.INVESTIGATING,
                false
        );
    }

    public static PostDraftReviewSession initial(String projectId,
                                                 ReviewFocus focus,
                                                 String operatorNote) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                focus == null ? ReviewWorkingSet.empty() : ReviewWorkingSet.fromAnchor(focus.chunkId()),
                TranscriptStore.empty(),
                HistoryLog.empty(),
                ReviewEvidenceBundle.empty(),
                ReviewWorkingSetContext.empty(),
                ReviewBoundaryWindow.empty(),
                Set.of(),
                Set.of(),
                ReviewVisitedObjects.empty(),
                List.of(),
                operatorNote,
                Set.of(),
                ReviewStrategy.KEEP,
                FocusReviewDiagnostics.empty(),
                ProjectIssueBacklog.empty(),
                ReviewAgentState.INITIALIZING,
                false
        );
    }

    public static PostDraftReviewSession investigating(String projectId,
                                                       ReviewFocus focus,
                                                       String operatorNote,
                                                       Set<ReviewProblemType> problemTypes,
                                                       List<String> evidenceSummaries) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                focus == null ? ReviewWorkingSet.empty() : ReviewWorkingSet.fromAnchor(focus.chunkId()),
                TranscriptStore.empty(),
                HistoryLog.empty(),
                new ReviewEvidenceBundle(
                        List.of(),
                        evidenceSummaries == null ? List.of() : evidenceSummaries,
                        List.of(),
                        List.of(),
                        List.of()
                ),
                ReviewWorkingSetContext.empty(),
                ReviewBoundaryWindow.empty(),
                Set.of(),
                Set.of(),
                ReviewVisitedObjects.empty(),
                List.of(),
                operatorNote,
                problemTypes == null ? Set.of() : problemTypes,
                ReviewStrategy.KEEP,
                FocusReviewDiagnostics.empty(),
                ProjectIssueBacklog.empty(),
                ReviewAgentState.INVESTIGATING,
                false
        );
    }

    public PostDraftReviewSession(String projectId,
                                  ReviewFocus focus,
                                  String operatorNote,
                                  List<String> readContextSummaries,
                                  Set<ReviewProblemType> problemTypes,
                                  List<String> evidenceSummaries,
                                  ReviewStrategy strategy,
                                  boolean waitingForHumanReview,
                                  ReviewAgentState state,
                                  List<ReviewAgentAction> actionTrail,
                                  Set<String> visitedObjects,
                                  List<String> keyEvidenceSummaries,
                                  List<String> conflictingEvidenceSummaries,
                                  List<String> evidenceGaps) {
        this(
                projectId,
                focus,
                focus == null ? ReviewWorkingSet.empty() : ReviewWorkingSet.fromAnchor(focus.chunkId()),
                TranscriptStore.empty(),
                HistoryLog.empty(),
                ReviewEvidenceBundle.fromLegacy(
                        readContextSummaries,
                        evidenceSummaries,
                        keyEvidenceSummaries,
                        conflictingEvidenceSummaries,
                        evidenceGaps
                ),
                ReviewWorkingSetContext.empty(),
                ReviewBoundaryWindow.empty(),
                Set.of(),
                Set.of(),
                ReviewVisitedObjects.from(visitedObjects),
                List.of(),
                operatorNote,
                problemTypes,
                strategy,
                FocusReviewDiagnostics.empty(),
                ProjectIssueBacklog.empty(),
                state == null ? ReviewAgentState.INVESTIGATING : state,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession(String projectId,
                                  ReviewFocus focus,
                                  String operatorNote,
                                  List<String> readContextSummaries,
                                  Set<ReviewProblemType> problemTypes,
                                  List<String> evidenceSummaries,
                                  ReviewStrategy strategy,
                                  boolean waitingForHumanReview,
                                  ReviewAgentState state,
                                  List<ReviewAgentAction> actionTrail,
                                  Set<String> visitedObjects,
                                  List<String> keyEvidenceSummaries,
                                  List<String> conflictingEvidenceSummaries,
                                  List<String> evidenceGaps,
                                  FocusAutonomyState autonomyState) {
        this(
                projectId,
                focus,
                focus == null ? ReviewWorkingSet.empty() : ReviewWorkingSet.fromAnchor(focus.chunkId()),
                TranscriptStore.empty(),
                HistoryLog.empty(),
                ReviewEvidenceBundle.fromLegacy(
                        readContextSummaries,
                        evidenceSummaries,
                        keyEvidenceSummaries,
                        conflictingEvidenceSummaries,
                        evidenceGaps
                ),
                ReviewWorkingSetContext.empty(),
                ReviewBoundaryWindow.empty(),
                Set.of(),
                Set.of(),
                ReviewVisitedObjects.from(visitedObjects),
                List.of(),
                operatorNote,
                problemTypes,
                strategy,
                toDiagnostics(autonomyState),
                ProjectIssueBacklog.empty(),
                state == null ? ReviewAgentState.INVESTIGATING : state,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession(String projectId,
                                  ReviewFocus focus,
                                  ReviewWorkingSet workingSet,
                                  ProjectIssueBacklog issueBacklog,
                                  TranscriptStore transcriptStore,
                                  HistoryLog historyLog,
                                  ReviewEvidenceBundle evidenceBundle,
                                  ReviewVisitedObjects reviewVisitedObjects,
                                  List<ReviewToolTrace> toolTraces,
                                  ReviewAgentConfig config,
                                  String operatorNote,
                                  Set<ReviewProblemType> problemTypes,
                                  ReviewStrategy strategy,
                                  boolean waitingForHumanReview,
                                  ReviewAgentState state,
                                  List<ReviewAgentAction> actionTrail,
                                  FocusAutonomyState autonomyState,
                                  ReviewAgentStopReason stopReason) {
        this(
                projectId,
                focus,
                workingSet,
                issueBacklog,
                transcriptStore,
                historyLog,
                evidenceBundle,
                ReviewWorkingSetContext.empty(),
                ReviewBoundaryWindow.empty(),
                Set.of(),
                Set.of(),
                reviewVisitedObjects,
                toolTraces,
                config,
                operatorNote,
                problemTypes,
                strategy,
                waitingForHumanReview,
                state,
                actionTrail,
                autonomyState,
                stopReason
        );
    }

    public PostDraftReviewSession(String projectId,
                                  ReviewFocus focus,
                                  ReviewWorkingSet workingSet,
                                  ProjectIssueBacklog issueBacklog,
                                  TranscriptStore transcriptStore,
                                  HistoryLog historyLog,
                                  ReviewEvidenceBundle evidenceBundle,
                                  ReviewWorkingSetContext workingSetContext,
                                  ReviewBoundaryWindow boundaryWindow,
                                  Set<String> readInFocusChunkIds,
                                  Set<String> verifiedInFocusChunkIds,
                                  ReviewVisitedObjects reviewVisitedObjects,
                                  List<ReviewToolTrace> toolTraces,
                                  ReviewAgentConfig config,
                                  String operatorNote,
                                  Set<ReviewProblemType> problemTypes,
                                  ReviewStrategy strategy,
                                  boolean waitingForHumanReview,
                                  ReviewAgentState state,
                                  List<ReviewAgentAction> actionTrail,
                                  FocusAutonomyState autonomyState,
                                  ReviewAgentStopReason stopReason) {
        this(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                toDiagnostics(autonomyState),
                issueBacklog,
                state == null ? ReviewAgentState.INVESTIGATING : state,
                waitingForHumanReview || stopReason == ReviewAgentStopReason.HUMAN_REVIEW_REQUIRED
        );
    }

    public PostDraftReviewSession {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (focus == null) {
            throw new IllegalArgumentException("focus must not be null");
        }
        workingSet = copyWorkingSet(workingSet);
        transcriptStore = copyTranscriptStore(transcriptStore);
        historyLog = copyHistoryLog(historyLog);
        evidenceBundle = copyEvidenceBundle(evidenceBundle);
        workingSetContext = copyWorkingSetContext(workingSetContext);
        boundaryWindow = copyBoundaryWindow(boundaryWindow);
        readInFocusChunkIds = copyChunkIdSet(readInFocusChunkIds);
        verifiedInFocusChunkIds = copyChunkIdSet(verifiedInFocusChunkIds);
        reviewVisitedObjects = copyVisitedObjects(reviewVisitedObjects);
        toolTraces = toolTraces == null ? List.of() : List.copyOf(toolTraces);
        operatorNote = operatorNote == null ? "" : operatorNote;
        problemTypes = problemTypes == null ? Set.of() : Set.copyOf(problemTypes);
        strategy = strategy == null ? ReviewStrategy.KEEP : strategy;
        diagnostics = diagnostics == null ? FocusReviewDiagnostics.empty() : diagnostics;
        issueBacklog = issueBacklog == null ? ProjectIssueBacklog.empty() : copyIssueBacklog(issueBacklog);
        observationState = normalizeObservationState(observationState, waitingForHumanReview);
        validateWorkingSetFocus(focus, workingSet);
    }

    public ReviewAgentConfig config() {
        return ReviewAgentConfig.defaultConfig();
    }

    public List<ReviewAgentAction> actionTrail() {
        return List.of();
    }

    public FocusAutonomyState autonomyState() {
        return new FocusAutonomyState(
                0,
                diagnostics.revisionAttemptCount(),
                diagnostics.selfCheckFailureCount(),
                diagnostics.localRejectionReasons()
        );
    }

    public ReviewAgentStopReason stopReason() {
        return waitingForHumanReview ? ReviewAgentStopReason.HUMAN_REVIEW_REQUIRED : ReviewAgentStopReason.NONE;
    }

    public PostDraftReviewSession withAutonomyState(FocusAutonomyState nextAutonomyState) {
        FocusAutonomyState normalized = Objects.requireNonNullElse(nextAutonomyState, FocusAutonomyState.initial());
        return withDiagnostics(new FocusReviewDiagnostics(
                normalized.revisionAttempts(),
                normalized.selfCheckFailures(),
                normalized.localFailureReasons()
        ));
    }

    public PostDraftReviewSession withDiagnostics(FocusReviewDiagnostics nextDiagnostics) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                nextDiagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession withTranscriptStore(TranscriptStore nextTranscriptStore) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                nextTranscriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession withWorkingSet(ReviewWorkingSet nextWorkingSet) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                nextWorkingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession withBoundaryWindow(ReviewBoundaryWindow nextBoundaryWindow) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                nextBoundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession withWorkingSetContext(ReviewWorkingSetContext nextWorkingSetContext) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                nextWorkingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession markChunksReadInFocus(Set<String> chunkIds) {
        ArrayList<String> merged = new ArrayList<>(readInFocusChunkIds);
        for (String chunkId : chunkIds == null ? Set.<String>of() : chunkIds) {
            if (chunkId != null && !chunkId.isBlank() && !merged.contains(chunkId.trim())) {
                merged.add(chunkId.trim());
            }
        }
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                Set.copyOf(merged),
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession withVerifiedInFocusChunkIds(Set<String> chunkIds) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                chunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession withIssueBacklog(ProjectIssueBacklog nextIssueBacklog) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                nextIssueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession withEvidenceBundle(ReviewEvidenceBundle nextEvidenceBundle) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                nextEvidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession appendTranscript(String entry) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore.append(entry),
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession appendHistory(String title, String detail) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog.add(title, detail),
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession appendToolTrace(ReviewToolTrace trace) {
        ArrayList<ReviewToolTrace> updated = new ArrayList<>(toolTraces);
        updated.add(trace);
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                List.copyOf(updated),
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession withStrategy(ReviewStrategy nextStrategy) {
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                nextStrategy,
                diagnostics,
                issueBacklog,
                observationState,
                waitingForHumanReview
        );
    }

    public PostDraftReviewSession withState(ReviewAgentState nextState) {
        boolean nextWaiting = nextState == ReviewAgentState.WAITING_HUMAN;
        return new PostDraftReviewSession(
                projectId,
                focus,
                workingSet,
                transcriptStore,
                historyLog,
                evidenceBundle,
                workingSetContext,
                boundaryWindow,
                readInFocusChunkIds,
                verifiedInFocusChunkIds,
                reviewVisitedObjects,
                toolTraces,
                operatorNote,
                problemTypes,
                strategy,
                diagnostics,
                issueBacklog,
                normalizeObservationState(nextState, nextWaiting),
                nextWaiting
        );
    }

    public PostDraftReviewSession withStopReason(ReviewAgentStopReason ignored) {
        return this;
    }

    public PostDraftReviewSession withInvestigatingState() {
        return withState(ReviewAgentState.INVESTIGATING);
    }

    public PostDraftReviewSession withEvaluatingState() {
        return withState(ReviewAgentState.EVALUATING);
    }

    public PostDraftReviewSession withRevisingState() {
        return withState(ReviewAgentState.REVISING);
    }

    public PostDraftReviewSession withWaitingHumanState() {
        return withState(ReviewAgentState.WAITING_HUMAN);
    }

    public ReviewAgentState state() {
        return observationState;
    }

    public List<String> readContextSummaries() {
        return evidenceBundle.readContextSummaries();
    }

    public List<String> evidenceSummaries() {
        return evidenceBundle.evidenceSummaries();
    }

    public Set<String> visitedObjects() {
        return reviewVisitedObjects.objectIds();
    }

    public List<String> keyEvidenceSummaries() {
        return evidenceBundle.keyEvidenceSummaries();
    }

    public List<String> conflictingEvidenceSummaries() {
        return evidenceBundle.conflictingEvidenceSummaries();
    }

    public List<String> evidenceGaps() {
        return evidenceBundle.evidenceGaps();
    }

    private static ReviewAgentState normalizeObservationState(ReviewAgentState state, boolean waitingForHumanReview) {
        if (waitingForHumanReview) {
            return ReviewAgentState.WAITING_HUMAN;
        }
        if (state == null || state == ReviewAgentState.WAITING_HUMAN) {
            return ReviewAgentState.INVESTIGATING;
        }
        return state;
    }

    private static void validateWorkingSetFocus(ReviewFocus focus, ReviewWorkingSet workingSet) {
        if (workingSet == null || workingSet.isEmpty()) {
            throw new IllegalArgumentException("workingSet must not be empty");
        }
        if (!focus.chunkId().equals(workingSet.anchorChunkId())) {
            throw new IllegalArgumentException("workingSet anchor must match focus chunkId");
        }
    }

    private static ReviewWorkingSet copyWorkingSet(ReviewWorkingSet workingSet) {
        if (workingSet == null) {
            throw new IllegalArgumentException("workingSet must not be null");
        }
        return new ReviewWorkingSet(workingSet.anchorChunkId(), workingSet.chunkIds());
    }

    private static ProjectIssueBacklog copyIssueBacklog(ProjectIssueBacklog issueBacklog) {
        return new ProjectIssueBacklog(issueBacklog.openIssues());
    }

    private static ReviewWorkingSetContext copyWorkingSetContext(ReviewWorkingSetContext workingSetContext) {
        if (workingSetContext == null) {
            throw new IllegalArgumentException("workingSetContext must not be null");
        }
        return new ReviewWorkingSetContext(workingSetContext.snapshots());
    }

    private static ReviewBoundaryWindow copyBoundaryWindow(ReviewBoundaryWindow boundaryWindow) {
        if (boundaryWindow == null) {
            return ReviewBoundaryWindow.empty();
        }
        return new ReviewBoundaryWindow(boundaryWindow.snapshots());
    }

    private static Set<String> copyChunkIdSet(Set<String> chunkIds) {
        if (chunkIds == null) {
            return Set.of();
        }
        ArrayList<String> normalized = new ArrayList<>();
        for (String chunkId : chunkIds) {
            if (chunkId != null && !chunkId.isBlank()) {
                String normalizedChunkId = chunkId.trim();
                if (!normalized.contains(normalizedChunkId)) {
                    normalized.add(normalizedChunkId);
                }
            }
        }
        return Set.copyOf(normalized);
    }

    private static FocusReviewDiagnostics toDiagnostics(FocusAutonomyState autonomyState) {
        if (autonomyState == null) {
            return FocusReviewDiagnostics.empty();
        }
        return new FocusReviewDiagnostics(
                autonomyState.revisionAttempts(),
                autonomyState.selfCheckFailures(),
                autonomyState.localFailureReasons()
        );
    }

    private static TranscriptStore copyTranscriptStore(TranscriptStore transcriptStore) {
        if (transcriptStore == null) {
            throw new IllegalArgumentException("transcriptStore must not be null");
        }
        return new TranscriptStore(transcriptStore.replay(), transcriptStore.flushed());
    }

    private static HistoryLog copyHistoryLog(HistoryLog historyLog) {
        if (historyLog == null) {
            throw new IllegalArgumentException("historyLog must not be null");
        }
        return new HistoryLog(historyLog.replay());
    }

    private static ReviewEvidenceBundle copyEvidenceBundle(ReviewEvidenceBundle evidenceBundle) {
        if (evidenceBundle == null) {
            throw new IllegalArgumentException("evidenceBundle must not be null");
        }
        return new ReviewEvidenceBundle(
                evidenceBundle.readContextSummaries(),
                evidenceBundle.evidenceSummaries(),
                evidenceBundle.keyEvidenceSummaries(),
                evidenceBundle.conflictingEvidenceSummaries(),
                evidenceBundle.evidenceGaps()
        );
    }

    private static ReviewVisitedObjects copyVisitedObjects(ReviewVisitedObjects reviewVisitedObjects) {
        if (reviewVisitedObjects == null) {
            throw new IllegalArgumentException("reviewVisitedObjects must not be null");
        }
        return new ReviewVisitedObjects(reviewVisitedObjects.objectIds());
    }
}
