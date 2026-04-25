package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewStatus;
import io.quillloom.application.postdraft.review.model.ReviewAgentConfig;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolExecutionResult;
import io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

public class AutonomousProjectReviewAgent {

    private final PostDraftReviewAgentReader reader;
    private final PostDraftReviewSessionFactory sessionFactory;
    private final PostDraftReviewProblemClassifier problemClassifier;
    private final ProjectFocusSelector focusSelector;
    private final PromptBackedNextStepDecisionProvider nextStepDecisionProvider;
    private final ReviewToolExecutor toolExecutor;
    private final ReviewRuntimeVisualizer runtimeVisualizer;
    private final ProjectReviewRuntimePersistenceHook persistenceHook;
    private final ReviewAgentConfig config;
    private final Duration maxWallClockDuration;
    private final LongSupplier nanoTimeSource;

    public AutonomousProjectReviewAgent(PostDraftReviewAgentReader reader,
                                        PostDraftReviewSessionFactory sessionFactory,
                                        PostDraftReviewProblemClassifier problemClassifier,
                                        ProjectFocusSelector focusSelector,
                                        PromptBackedNextStepDecisionProvider nextStepDecisionProvider,
                                        ReviewToolExecutor toolExecutor,
                                        ReviewRuntimeVisualizer runtimeVisualizer) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                focusSelector,
                nextStepDecisionProvider,
                toolExecutor,
                runtimeVisualizer,
                ProjectReviewRuntimePersistenceHook.noop(),
                ReviewAgentConfig.defaultConfig(),
                300,
                System::nanoTime
        );
    }

    public AutonomousProjectReviewAgent(PostDraftReviewAgentReader reader,
                                        PostDraftReviewSessionFactory sessionFactory,
                                        PostDraftReviewProblemClassifier problemClassifier,
                                        ProjectFocusSelector focusSelector,
                                        PromptBackedNextStepDecisionProvider nextStepDecisionProvider,
                                        ReviewToolExecutor toolExecutor,
                                        ReviewRuntimeVisualizer runtimeVisualizer,
                                        ProjectReviewRuntimePersistenceHook persistenceHook,
                                        ReviewAgentConfig config) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                focusSelector,
                nextStepDecisionProvider,
                toolExecutor,
                runtimeVisualizer,
                persistenceHook,
                config,
                300,
                System::nanoTime
        );
    }

    public AutonomousProjectReviewAgent(PostDraftReviewAgentReader reader,
                                        PostDraftReviewSessionFactory sessionFactory,
                                        PostDraftReviewProblemClassifier problemClassifier,
                                        ProjectFocusSelector focusSelector,
                                        PromptBackedNextStepDecisionProvider nextStepDecisionProvider,
                                        ReviewToolExecutor toolExecutor,
                                        ReviewRuntimeVisualizer runtimeVisualizer,
                                        ProjectReviewRuntimePersistenceHook persistenceHook,
                                        ReviewAgentConfig config,
                                        long maxWallClockMinutes) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                focusSelector,
                nextStepDecisionProvider,
                toolExecutor,
                runtimeVisualizer,
                persistenceHook,
                config,
                maxWallClockMinutes,
                System::nanoTime
        );
    }

    public AutonomousProjectReviewAgent(PostDraftReviewAgentReader reader,
                                        PostDraftReviewSessionFactory sessionFactory,
                                        PostDraftReviewProblemClassifier problemClassifier,
                                        ProjectFocusSelector focusSelector,
                                        PromptBackedNextStepDecisionProvider nextStepDecisionProvider,
                                        ReviewToolExecutor toolExecutor,
                                        ReviewRuntimeVisualizer runtimeVisualizer,
                                        ProjectReviewRuntimePersistenceHook persistenceHook,
                                        ReviewAgentConfig config,
                                        long maxWallClockMinutes,
                                        LongSupplier nanoTimeSource) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.problemClassifier = Objects.requireNonNull(problemClassifier, "problemClassifier");
        this.focusSelector = Objects.requireNonNull(focusSelector, "focusSelector");
        this.nextStepDecisionProvider = Objects.requireNonNull(nextStepDecisionProvider, "nextStepDecisionProvider");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.runtimeVisualizer = Objects.requireNonNull(runtimeVisualizer, "runtimeVisualizer");
        this.persistenceHook = Objects.requireNonNull(persistenceHook, "persistenceHook");
        this.config = Objects.requireNonNull(config, "config");
        if (maxWallClockMinutes < 0) {
            throw new IllegalArgumentException("maxWallClockMinutes must be >= 0");
        }
        this.maxWallClockDuration = Duration.ofMinutes(maxWallClockMinutes);
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource");
    }

    public ProjectReviewRuntimeSession run(ProjectReviewRuntimeSession runtime,
                                           String operatorNote) {
        Objects.requireNonNull(runtime, "runtime");
        ProjectReviewRuntimeSession current = runtime;
        String normalizedOperatorNote = operatorNote == null ? "" : operatorNote.trim();
        long startedAtNanos = nanoTimeSource.getAsLong();
        runtimeVisualizer.projectStarted(current);
        while (true) {
            if (current.status() != ProjectReviewStatus.ACTIVE) {
                runtimeVisualizer.projectFinished(current);
                return current;
            }

            if (hasWallClockTimedOut(startedAtNanos)) {
                ProjectReviewRuntimeSession previous = current;
                current = current.failWallClockTimeout(
                        "maxWallClockMinutes=" + maxWallClockDuration.toMinutes()
                                + ", completedChunkCount=" + current.completedChunkOutcomes().size()
                                + ", pendingChunkCount=" + current.pendingChunkIds().size()
                );
                persistenceHook.afterTransition(previous, current);
                runtimeVisualizer.projectFinished(current);
                return current;
            }

            if (current.currentFocusSession().isEmpty()) {
                if (current.currentFocusChunkId().isEmpty()) {
                    if (current.canAutoCompletePendingEmptyProject()) {
                        ProjectReviewRuntimeSession completedRuntime = current.completeProject();
                        runtimeVisualizer.projectFinished(completedRuntime);
                        return completedRuntime;
                    }
                    if (current.pendingChunkIds().isEmpty()) {
                        ProjectReviewRuntimeSession failedRuntime = current.failNoProgress(
                                "pendingChunkCount=0 but blocking backlog remains"
                        );
                        persistenceHook.afterTransition(current, failedRuntime);
                        runtimeVisualizer.projectFinished(failedRuntime);
                        return failedRuntime;
                    }
                    current = focusSelector.selectNext(current);
                    if (current.currentFocusChunkId().isPresent()) {
                        runtimeVisualizer.focusSelected(current);
                    }
                    continue;
                }
                current = ensureFocusSession(current, normalizedOperatorNote);
            }

            PostDraftReviewSession focusSession = current.currentFocusSession().orElseThrow();
            ReviewToolDecision decision;
            try {
                decision = nextStepDecisionProvider.decide(focusSession);
            } catch (ReviewAgentNextStepStructuredOutputException
                     | RecordConfirmedTermsProposalException
                     | RecordConfirmedTermsAssemblyException ex) {
                ProjectReviewRuntimeSession previous = current;
                current = current.deferCurrentFocusFailure(
                        structuredFailureCode(ex),
                        summarizeContainableFailure(ex),
                        summarizeContainableIssue(current, ex)
                );
                persistenceHook.afterTransition(previous, current);
                continue;
            } catch (LlmStructuredOutputException ex) {
                ProjectReviewRuntimeSession previous = current;
                current = current.failLlmCall(summarizeLlmFailure(ex));
                persistenceHook.afterTransition(previous, current);
                runtimeVisualizer.projectFinished(current);
                return current;
            } catch (RuntimeException ex) {
                ProjectReviewRuntimeSession previous = current;
                current = current.failLlmCall(summarizeLlmFailure(ex));
                persistenceHook.afterTransition(previous, current);
                runtimeVisualizer.projectFinished(current);
                return current;
            }
            runtimeVisualizer.toolCalled(current, decision);
            ReviewToolExecutionResult execution;
            try {
                execution = toolExecutor.execute(current, decision);
            } catch (RuntimeException ex) {
                if (!isLlmBackedExecution(decision.toolName())) {
                    throw ex;
                }
                ProjectReviewRuntimeSession previous = current;
                current = current.failLlmCall(summarizeLlmFailure(ex));
                persistenceHook.afterTransition(previous, current);
                runtimeVisualizer.projectFinished(current);
                return current;
            }
            runtimeVisualizer.toolCompleted(current, execution);
            ProjectReviewRuntimeSession previous = current;
            current = execution.nextRuntime();
            persistenceHook.afterTransition(previous, current);
            if (current.canAutoCompletePendingEmptyProject()) {
                ProjectReviewRuntimeSession completedRuntime = current.completeProject();
                persistenceHook.afterTransition(current, completedRuntime);
                runtimeVisualizer.projectFinished(completedRuntime);
                return completedRuntime;
            }
            if (current.status() == ProjectReviewStatus.ACTIVE
                    && current.pendingChunkIds().isEmpty()
                    && !current.issueBacklog().openIssues().isEmpty()) {
                ProjectReviewRuntimeSession failedRuntime = current.failNoProgress(
                        "pendingChunkCount=0 but blocking backlog remains"
                );
                persistenceHook.afterTransition(current, failedRuntime);
                runtimeVisualizer.projectFinished(failedRuntime);
                return failedRuntime;
            }

            current = compactFocusTranscriptIfNeeded(current);
        }
    }

    public ProjectReviewRuntimeSession resume(ProjectReviewRuntimeSession runtime,
                                              String humanReviewNote) {
        Objects.requireNonNull(runtime, "runtime");
        if (runtime.status() != ProjectReviewStatus.WAITING_HUMAN) {
            throw new IllegalStateException("resume requires WAITING_HUMAN status, got: " + runtime.status());
        }
        String normalizedNote = humanReviewNote == null ? "" : humanReviewNote.trim();
        ProjectReviewRuntimeSession resumed = runtime.resumeFromHumanReview(normalizedNote);
        return run(resumed, "");
    }

    private ProjectReviewRuntimeSession compactFocusTranscriptIfNeeded(ProjectReviewRuntimeSession runtime) {
        if (runtime.status() != ProjectReviewStatus.ACTIVE) {
            return runtime;
        }
        if (runtime.currentFocusSession().isEmpty()) {
            return runtime;
        }
        PostDraftReviewSession session = runtime.currentFocusSession().orElseThrow();
        int transcriptSize = session.transcriptStore().replay().size();
        int evidenceSize = session.evidenceBundle().totalEntries();
        if (transcriptSize <= config.compactAfterTurns() && evidenceSize <= config.compactAfterTurns()) {
            return runtime;
        }
        String compactSummary = buildCompactSummary(session);
        PostDraftReviewSession compactedSession = session;
        if (transcriptSize > config.compactAfterTurns()) {
            compactedSession = compactedSession.withTranscriptStore(
                    compactedSession.transcriptStore()
                            .compact(config.compactKeepLast())
                            .prepend(compactSummary)
            );
        }
        if (evidenceSize > config.compactAfterTurns()) {
            compactedSession = compactedSession.withEvidenceBundle(
                    compactedSession.evidenceBundle().compact(config.compactKeepLastEvidence())
            );
        }
        return runtime.withCurrentFocusSession(
                compactedSession,
                runtime.currentFocusRound(),
                compactedSession.state()
        );
    }

    private String buildCompactSummary(PostDraftReviewSession session) {
        return "[compact] completedTurns=%d, currentStrategy=%s, visitedChunks=%s, keyFindings=%s".formatted(
                session.transcriptStore().replay().size(),
                session.strategy(),
                session.workingSet().chunkIds(),
                session.keyEvidenceSummaries().isEmpty()
                        ? "(none)"
                        : String.join("; ", session.keyEvidenceSummaries())
        );
    }

    private ProjectReviewRuntimeSession ensureFocusSession(ProjectReviewRuntimeSession runtime,
                                                           String operatorNote) {
        if (runtime.currentFocusSession().isPresent()) {
            return runtime;
        }
        String chunkId = runtime.currentFocusChunkId()
                .orElseThrow(() -> new IllegalStateException("focus selection requires currentFocusChunkId"));
        PostDraftChunkRecord chunk = reader.loadChunkById(runtime.projectId(), chunkId)
                .orElseThrow(() -> new IllegalStateException("Chunk not found for focus=" + chunkId));
        PostDraftReviewSession session = sessionFactory.createProjectFocusSession(
                runtime.projectId(),
                operatorNote,
                chunk,
                problemClassifier.classify(chunk),
                buildSeedEvidence(chunk)
        );
        io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot anchorSnapshot =
                ReviewChunkSnapshotFormatter.toContextSnapshot(chunk, true);
        session = session
                .withWorkingSetContext(new io.quillloom.application.postdraft.review.model.ReviewWorkingSetContext(List.of(anchorSnapshot)))
                .withBoundaryWindow(new io.quillloom.application.postdraft.review.model.ReviewBoundaryWindow(List.of(anchorSnapshot)));
        return runtime.withInvestigatingFocusSession(session, runtime.currentFocusRound());
    }

    private List<String> buildSeedEvidence(PostDraftChunkRecord chunk) {
        return List.of(ReviewChunkSnapshotFormatter.renderAnchorChunk(chunk));
    }

    private String summarizeLlmFailure(RuntimeException exception) {
        if (exception == null) {
            return "unknown";
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.trim();
    }

    private String summarizeContainableFailure(LlmStructuredOutputException exception) {
        String message = summarizeLlmFailure(exception);
        int maxLength = 240;
        return message.length() <= maxLength ? message : message.substring(0, maxLength) + "...(truncated)";
    }

    private String summarizeContainableIssue(ProjectReviewRuntimeSession runtime,
                                             LlmStructuredOutputException exception) {
        String chunkId = runtime.currentFocusChunkId().orElse("unknown-chunk");
        return "chunkId=" + chunkId
                + ", failureCode=" + structuredFailureCode(exception)
                + ", rawOutput=" + extractRawOutput(exception.getMessage())
                + ", error=" + summarizeLlmFailure(exception);
    }

    private String structuredFailureCode(LlmStructuredOutputException exception) {
        if (exception instanceof ReviewAgentNextStepStructuredOutputException) {
            return "NEXT_STEP_STRUCTURED_OUTPUT_FAILED";
        }
        if (exception instanceof RecordConfirmedTermsProposalException) {
            return "RECORD_CONFIRMED_TERMS_PROPOSAL_FAILED";
        }
        if (exception instanceof RecordConfirmedTermsAssemblyException) {
            return "RECORD_CONFIRMED_TERMS_ASSEMBLY_FAILED";
        }
        return "LLM_STRUCTURED_OUTPUT_FAILED";
    }

    private String extractRawOutput(String message) {
        if (message == null || message.isBlank()) {
            return "(none)";
        }
        int rawOutputIndex = message.indexOf("rawOutput=");
        if (rawOutputIndex < 0) {
            return "(none)";
        }
        return message.substring(rawOutputIndex + "rawOutput=".length()).trim();
    }

    private boolean hasWallClockTimedOut(long startedAtNanos) {
        if (maxWallClockDuration.isZero()) {
            return false;
        }
        Duration elapsed = Duration.ofNanos(nanoTimeSource.getAsLong() - startedAtNanos);
        return elapsed.compareTo(maxWallClockDuration) >= 0;
    }

    private boolean isLlmBackedExecution(String toolName) {
        return "evaluate_focus".equals(toolName)
                || "draft_revision".equals(toolName);
    }
}

