package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record ProjectReviewRuntimeSession(
        String projectId,
        List<String> pendingChunkIds,
        List<ProjectChunkReviewOutcome> completedChunkOutcomes,
        Optional<String> selectedFocusChunkId,
        Optional<PostDraftReviewSession> currentFocusSession,
        TranscriptStore transcriptStore,
        HistoryLog historyLog,
        List<String> processTrail,
        Optional<HumanReviewRequest> humanReviewRequest,
        ProjectReviewStatus status,
        ReviewProjectStopReason stopReason,
        ProjectIssueBacklog issueBacklog,
        int currentFocusRound
) {

    public ProjectReviewRuntimeSession(String projectId,
                                       List<String> pendingChunkIds,
                                       List<ProjectChunkReviewOutcome> completedChunkOutcomes,
                                       Optional<PostDraftReviewSession> currentFocusSession,
                                       TranscriptStore transcriptStore,
                                       HistoryLog historyLog,
                                       List<String> processTrail,
                                       Optional<HumanReviewRequest> humanReviewRequest,
                                       ProjectReviewStatus status,
                                       ReviewProjectStopReason stopReason) {
        this(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                currentFocusSession == null || currentFocusSession.isEmpty()
                        ? Optional.empty()
                        : Optional.of(currentFocusSession.orElseThrow().focus().chunkId()),
                currentFocusSession,
                transcriptStore,
                historyLog,
                processTrail,
                humanReviewRequest,
                status,
                stopReason,
                ProjectIssueBacklog.empty(),
                currentFocusSession == null || currentFocusSession.isEmpty() ? 0 : 1
        );
    }

    public ProjectReviewRuntimeSession {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (pendingChunkIds == null) {
            throw new IllegalArgumentException("pendingChunkIds must not be null");
        }
        if (completedChunkOutcomes == null) {
            throw new IllegalArgumentException("completedChunkOutcomes must not be null");
        }
        if (currentFocusRound < 0) {
            throw new IllegalArgumentException("currentFocusRound must be >= 0");
        }
        selectedFocusChunkId = selectedFocusChunkId == null ? Optional.empty() : selectedFocusChunkId;
        currentFocusSession = currentFocusSession == null ? Optional.empty() : currentFocusSession;
        transcriptStore = transcriptStore == null ? TranscriptStore.empty() : copyTranscriptStore(transcriptStore);
        historyLog = historyLog == null ? HistoryLog.empty() : copyHistoryLog(historyLog);
        processTrail = processTrail == null ? List.of() : List.copyOf(processTrail);
        humanReviewRequest = humanReviewRequest == null ? Optional.empty() : humanReviewRequest;
        status = status == null ? ProjectReviewStatus.ACTIVE : status;
        stopReason = stopReason == null ? ReviewProjectStopReason.NONE : stopReason;
        issueBacklog = issueBacklog == null ? ProjectIssueBacklog.empty() : new ProjectIssueBacklog(issueBacklog.openIssues());

        Set<String> uniquePendingChunkIds = new LinkedHashSet<>();
        for (String chunkId : pendingChunkIds) {
            if (chunkId == null || chunkId.isBlank()) {
                throw new IllegalArgumentException("pendingChunkIds must not contain blank chunkId");
            }
            if (!uniquePendingChunkIds.add(chunkId)) {
                throw new IllegalArgumentException("pendingChunkIds must not contain duplicate chunkId");
            }
        }
        pendingChunkIds = List.copyOf(pendingChunkIds);
        completedChunkOutcomes = List.copyOf(completedChunkOutcomes);

        if (selectedFocusChunkId.isPresent() && selectedFocusChunkId.orElseThrow().isBlank()) {
            throw new IllegalArgumentException("selectedFocusChunkId must not be blank");
        }
        if (currentFocusSession.isPresent() && selectedFocusChunkId.isPresent()) {
            String sessionChunkId = currentFocusSession.orElseThrow().focus().chunkId();
            if (!sessionChunkId.equals(selectedFocusChunkId.orElseThrow())) {
                throw new IllegalArgumentException("selectedFocusChunkId must match currentFocusSession focus chunkId");
            }
        }

        if (status == ProjectReviewStatus.WAITING_HUMAN) {
            if (humanReviewRequest.isEmpty()) {
                throw new IllegalArgumentException("WAITING_HUMAN status requires humanReviewRequest");
            }
            if (stopReason != ReviewProjectStopReason.HUMAN_REVIEW_REQUIRED) {
                throw new IllegalArgumentException("WAITING_HUMAN status requires HUMAN_REVIEW_REQUIRED stopReason");
            }
        } else if (humanReviewRequest.isPresent()) {
            throw new IllegalArgumentException("humanReviewRequest requires WAITING_HUMAN status");
        }

        if (status == ProjectReviewStatus.COMPLETED && stopReason != ReviewProjectStopReason.PROJECT_COMPLETED) {
            throw new IllegalArgumentException("COMPLETED status requires PROJECT_COMPLETED stopReason");
        }
        if (status == ProjectReviewStatus.FAILED
                && stopReason != ReviewProjectStopReason.FAILED
                && stopReason != ReviewProjectStopReason.NO_PROGRESS
                && stopReason != ReviewProjectStopReason.LLM_CALL_FAILED
                && stopReason != ReviewProjectStopReason.WALL_CLOCK_TIMEOUT) {
            throw new IllegalArgumentException("FAILED status requires FAILED, NO_PROGRESS, LLM_CALL_FAILED or WALL_CLOCK_TIMEOUT stopReason");
        }
    }

    public static ProjectReviewRuntimeSession start(String projectId, List<String> orderedChunkIds) {
        return new ProjectReviewRuntimeSession(
                projectId,
                orderedChunkIds,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                TranscriptStore.empty(),
                HistoryLog.empty(),
                List.of(),
                Optional.empty(),
                ProjectReviewStatus.ACTIVE,
                ReviewProjectStopReason.NONE,
                ProjectIssueBacklog.empty(),
                0
        );
    }

    public static ProjectReviewRuntimeSession initialize(String projectId, List<String> orderedChunkIds) {
        return start(projectId, orderedChunkIds);
    }

    public Optional<ReviewStrategy> currentStrategy() {
        return currentFocusSession.map(PostDraftReviewSession::strategy);
    }

    public Optional<String> currentFocusChunkId() {
        if (selectedFocusChunkId.isPresent()) {
            return selectedFocusChunkId;
        }
        return currentFocusSession.map(session -> session.focus().chunkId());
    }

    public ReviewWorkingSet workingSet() {
        return currentFocusSession.map(PostDraftReviewSession::workingSet).orElseGet(ReviewWorkingSet::empty);
    }

    public int pendingChunkCount() {
        return pendingChunkIds.size();
    }

    public int completedChunkCount() {
        return completedChunkOutcomes.size();
    }

    public boolean currentFocusChunkStillPending() {
        return currentFocusChunkId()
                .map(pendingChunkIds::contains)
                .orElse(false);
    }

    public boolean canAutoCompletePendingEmptyProject() {
        return status == ProjectReviewStatus.ACTIVE
                && pendingChunkIds.isEmpty()
                && issueBacklog.openIssues().isEmpty();
    }

    public UsageSummary totalUsage() {
        return UsageSummary.empty();
    }

    public ReviewAgentConfig config() {
        return ReviewAgentConfig.defaultConfig();
    }

    public ReviewAgentState state() {
        return switch (status) {
            case WAITING_HUMAN -> ReviewAgentState.WAITING_HUMAN;
            case COMPLETED -> ReviewAgentState.COMPLETED;
            case FAILED -> ReviewAgentState.FAILED;
            case ACTIVE -> currentFocusSession.map(PostDraftReviewSession::state).orElse(ReviewAgentState.SELECTING_FOCUS);
        };
    }

    public ReviewAgentStopReason agentStopReason() {
        return switch (stopReason) {
            case NONE -> ReviewAgentStopReason.NONE;
            case HUMAN_REVIEW_REQUIRED -> ReviewAgentStopReason.HUMAN_REVIEW_REQUIRED;
            case PROJECT_COMPLETED -> ReviewAgentStopReason.PROJECT_COMPLETED;
            case NO_PROGRESS, LLM_CALL_FAILED, WALL_CLOCK_TIMEOUT, FAILED -> ReviewAgentStopReason.FAILED;
        };
    }

    public boolean isInitializingRuntime() {
        return status == ProjectReviewStatus.ACTIVE
                && currentFocusSession.isEmpty()
                && currentFocusChunkId().isEmpty()
                && processTrail.isEmpty();
    }

    public boolean isSelectingFocusRuntime() {
        return status == ProjectReviewStatus.ACTIVE
                && currentFocusSession.isEmpty()
                && currentFocusChunkId().isEmpty()
                && !pendingChunkIds.isEmpty();
    }

    public boolean isFinalizingRuntime() {
        return status == ProjectReviewStatus.COMPLETED;
    }

    public ProjectReviewRuntimeSession withState(ReviewAgentState nextState) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                transcriptStore,
                historyLog,
                processTrail,
                nextState == ReviewAgentState.WAITING_HUMAN ? humanReviewRequest : Optional.empty(),
                toStatus(nextState),
                nextState == ReviewAgentState.WAITING_HUMAN ? ReviewProjectStopReason.HUMAN_REVIEW_REQUIRED : stopReason,
                issueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession activateFocus(PostDraftReviewSession focusSession) {
        return withInvestigatingFocusSession(focusSession, 0);
    }

    public ProjectReviewRuntimeSession withSelectedFocus(String chunkId) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                Optional.of(chunkId),
                Optional.empty(),
                transcriptStore,
                historyLog,
                appendProcessTrail("selectFocus=" + chunkId),
                Optional.empty(),
                ProjectReviewStatus.ACTIVE,
                ReviewProjectStopReason.NONE,
                issueBacklog,
                0
        );
    }

    public ProjectReviewRuntimeSession withCurrentFocusSession(PostDraftReviewSession focusSession,
                                                              int focusRound,
                                                              ReviewAgentState ignoredState) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                Optional.of(focusSession.focus().chunkId()),
                Optional.of(focusSession),
                transcriptStore,
                historyLog,
                processTrail,
                Optional.empty(),
                ProjectReviewStatus.ACTIVE,
                ReviewProjectStopReason.NONE,
                issueBacklog,
                focusRound
        );
    }

    public ProjectReviewRuntimeSession withInvestigatingFocusSession(PostDraftReviewSession focusSession,
                                                                     int focusRound) {
        return withCurrentFocusSession(focusSession.withInvestigatingState(), focusRound, ReviewAgentState.INVESTIGATING);
    }

    public ProjectReviewRuntimeSession enterSelectingFocus() {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                Optional.empty(),
                Optional.empty(),
                transcriptStore,
                historyLog,
                appendProcessTrail("selectingFocus"),
                Optional.empty(),
                ProjectReviewStatus.ACTIVE,
                ReviewProjectStopReason.NONE,
                issueBacklog,
                0
        );
    }

    public ProjectReviewRuntimeSession markChunkCompleted(ProjectChunkReviewOutcome outcome) {
        return completeWorkingSet(List.of(outcome));
    }

    public ProjectReviewRuntimeSession withHumanReviewRequest(HumanReviewRequest request) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                transcriptStore,
                historyLog,
                appendProcessTrail("waitingHuman=" + request.requestReason()),
                Optional.of(request),
                ProjectReviewStatus.WAITING_HUMAN,
                ReviewProjectStopReason.HUMAN_REVIEW_REQUIRED,
                issueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession replaceHumanReviewRequest(HumanReviewRequest request) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                transcriptStore,
                historyLog,
                processTrail,
                Optional.of(request),
                ProjectReviewStatus.WAITING_HUMAN,
                ReviewProjectStopReason.HUMAN_REVIEW_REQUIRED,
                issueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession completeProject() {
        if (!pendingChunkIds.isEmpty()) {
            throw new IllegalStateException("pendingChunkIds must be empty before completing project");
        }
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                Optional.empty(),
                Optional.empty(),
                transcriptStore,
                historyLog,
                appendProcessTrail("projectCompleted"),
                Optional.empty(),
                ProjectReviewStatus.COMPLETED,
                ReviewProjectStopReason.PROJECT_COMPLETED,
                issueBacklog,
                0
        );
    }

    public ProjectReviewRuntimeSession failProject(String reason) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                transcriptStore,
                historyLog,
                appendProcessTrail("failed=" + normalizeProcess(reason)),
                Optional.empty(),
                ProjectReviewStatus.FAILED,
                ReviewProjectStopReason.FAILED,
                issueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession failNoProgress(String reason) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                transcriptStore,
                historyLog,
                appendProcessTrail("noProgress=" + normalizeProcess(reason)),
                Optional.empty(),
                ProjectReviewStatus.FAILED,
                ReviewProjectStopReason.NO_PROGRESS,
                issueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession failLlmCall(String reason) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                transcriptStore,
                historyLog,
                appendProcessTrail("llmCallFailed=" + normalizeProcess(reason)),
                Optional.empty(),
                ProjectReviewStatus.FAILED,
                ReviewProjectStopReason.LLM_CALL_FAILED,
                issueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession failWallClockTimeout(String reason) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                transcriptStore,
                historyLog,
                appendProcessTrail("wallClockTimeout=" + normalizeProcess(reason)),
                Optional.empty(),
                ProjectReviewStatus.FAILED,
                ReviewProjectStopReason.WALL_CLOCK_TIMEOUT,
                issueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession deferCurrentFocusFailure(String failureCode,
                                                               String diagnosticSummary,
                                                               String issueSummary) {
        String focusChunkId = currentFocusChunkId()
                .orElseThrow(() -> new IllegalStateException("deferCurrentFocusFailure requires current focus chunk"));
        List<String> remainingChunkIds = pendingChunkIds.stream()
                .filter(chunkId -> !chunkId.equals(focusChunkId))
                .toList();
        DeferredReviewIssue issue = new DeferredReviewIssue(
                focusChunkId + ":" + normalizeProcess(failureCode),
                focusChunkId,
                normalizeProcess(issueSummary)
        );
        ProjectIssueBacklog updatedBacklog = issueBacklog.add(issue);
        ProjectReviewRuntimeSession next = new ProjectReviewRuntimeSession(
                projectId,
                remainingChunkIds,
                completedChunkOutcomes,
                Optional.empty(),
                Optional.empty(),
                transcriptStore,
                historyLog,
                appendProcessTrail("focusFailed="
                        + focusChunkId
                        + ", failureCode="
                        + normalizeProcess(failureCode)
                        + ", diagnosticSummary="
                        + normalizeProcess(diagnosticSummary)),
                Optional.empty(),
                ProjectReviewStatus.ACTIVE,
                ReviewProjectStopReason.NONE,
                updatedBacklog,
                0
        );
        return remainingChunkIds.isEmpty() ? next : next.enterSelectingFocus();
    }

    public ProjectReviewRuntimeSession appendProcess(String entry) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                transcriptStore,
                historyLog,
                appendProcessTrail(entry),
                humanReviewRequest,
                status,
                stopReason,
                issueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession withIssueBacklog(ProjectIssueBacklog nextIssueBacklog) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                transcriptStore,
                historyLog,
                processTrail,
                humanReviewRequest,
                status,
                stopReason,
                nextIssueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession withTranscriptStore(TranscriptStore nextTranscriptStore) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                nextTranscriptStore,
                historyLog,
                processTrail,
                humanReviewRequest,
                status,
                stopReason,
                issueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession withHistoryLog(HistoryLog nextHistoryLog) {
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                currentFocusSession,
                transcriptStore,
                nextHistoryLog,
                processTrail,
                humanReviewRequest,
                status,
                stopReason,
                issueBacklog,
                currentFocusRound
        );
    }

    public ProjectReviewRuntimeSession completeWorkingSet(List<ProjectChunkReviewOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            throw new IllegalArgumentException("outcomes must not be empty");
        }
        Set<String> completedChunkIds = outcomes.stream()
                .map(ProjectChunkReviewOutcome::chunkId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!pendingChunkIds.containsAll(completedChunkIds)) {
            throw new IllegalArgumentException("completed chunkIds must exist in pendingChunkIds");
        }

        ArrayList<ProjectChunkReviewOutcome> updatedOutcomes = new ArrayList<>(completedChunkOutcomes);
        updatedOutcomes.addAll(outcomes);
        List<String> remainingChunkIds = pendingChunkIds.stream()
                .filter(chunkId -> !completedChunkIds.contains(chunkId))
                .toList();

        ProjectReviewRuntimeSession next = new ProjectReviewRuntimeSession(
                projectId,
                remainingChunkIds,
                List.copyOf(updatedOutcomes),
                remainingChunkIds.isEmpty() ? selectedFocusChunkId : Optional.empty(),
                remainingChunkIds.isEmpty() ? currentFocusSession : Optional.empty(),
                transcriptStore,
                historyLog,
                appendProcessTrail("completedWorkingSet=" + String.join(",", completedChunkIds)),
                Optional.empty(),
                ProjectReviewStatus.ACTIVE,
                ReviewProjectStopReason.NONE,
                issueBacklog,
                remainingChunkIds.isEmpty() ? currentFocusRound : 0
        );
        return remainingChunkIds.isEmpty() ? next : next.enterSelectingFocus();
    }

    public ProjectReviewRuntimeSession resumeFromHumanReview(String humanReviewNote) {
        if (status != ProjectReviewStatus.WAITING_HUMAN) {
            throw new IllegalStateException("resumeFromHumanReview requires WAITING_HUMAN status, got: " + status);
        }
        String normalizedNote = humanReviewNote == null ? "" : humanReviewNote.trim();
        PostDraftReviewSession resumedFocusSession = currentFocusSession
                .map(session -> session
                        .withState(ReviewAgentState.INVESTIGATING)
                        .appendTranscript("[human_review] 人工意见: " + normalizedNote)
                        .appendHistory("human_review", normalizedNote)
                )
                .orElse(null);
        TranscriptStore resumedTranscript = transcriptStore.append("[human_review] 人工意见: " + normalizedNote);
        HistoryLog resumedHistory = historyLog.add("human_review", normalizedNote);
        return new ProjectReviewRuntimeSession(
                projectId,
                pendingChunkIds,
                completedChunkOutcomes,
                selectedFocusChunkId,
                Optional.ofNullable(resumedFocusSession),
                resumedTranscript,
                resumedHistory,
                appendProcessTrail("resumed_from_human_review"),
                Optional.empty(),
                ProjectReviewStatus.ACTIVE,
                ReviewProjectStopReason.NONE,
                issueBacklog,
                currentFocusRound
        );
    }

    private List<String> appendProcessTrail(String entry) {
        ArrayList<String> updated = new ArrayList<>(processTrail);
        if (entry != null && !entry.isBlank()) {
            updated.add(entry.trim());
        }
        return List.copyOf(updated);
    }

    private static ProjectReviewStatus toStatus(ReviewAgentState state) {
        if (state == null) {
            return ProjectReviewStatus.ACTIVE;
        }
        return switch (state) {
            case WAITING_HUMAN -> ProjectReviewStatus.WAITING_HUMAN;
            case COMPLETED, FINALIZING -> ProjectReviewStatus.COMPLETED;
            case FAILED -> ProjectReviewStatus.FAILED;
            default -> ProjectReviewStatus.ACTIVE;
        };
    }

    private static String normalizeProcess(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static TranscriptStore copyTranscriptStore(TranscriptStore transcriptStore) {
        return new TranscriptStore(transcriptStore.replay(), transcriptStore.flushed());
    }

    private static HistoryLog copyHistoryLog(HistoryLog historyLog) {
        return new HistoryLog(historyLog.replay());
    }
}
