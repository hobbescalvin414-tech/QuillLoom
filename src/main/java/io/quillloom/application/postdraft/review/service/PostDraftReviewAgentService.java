package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.command.StartProjectPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.command.StartPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.StoredReviewSession;
import io.quillloom.application.postdraft.review.port.out.HumanInTheLoopGateway;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewBaselineStore;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentTermWriter;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentWriter;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.port.out.ReviewSessionStore;
import io.quillloom.application.postdraft.review.prompt.EvaluationPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.InvestigationPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.RevisionPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.RevisionSelfCheckPromptBuilder;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class PostDraftReviewAgentService {

    private final PostDraftReviewAgentReader reader;
    private final PostDraftReviewSessionFactory sessionFactory;
    private final PostDraftReviewProblemClassifier problemClassifier;
    private final PostDraftReviewProcessSummaryAssembler summaryAssembler;
    private final HumanInTheLoopGateway humanGateway;
    private final PostDraftReviewAgentWriter writer;
    private final PostDraftReviewAgentTermWriter termWriter;
    private final AutonomousProjectReviewAgent autonomousAgent;
    private final ProjectReviewOutputAssembler projectOutputAssembler;
    private final ReviewSessionStore reviewSessionStore;
    private final ProjectReviewRuntimePersistenceHook persistenceHook;
    private final PostDraftReviewBaselineStore baselineStore;
    private final ReviewAgentPromptDumpWriter promptDumpWriter;
    private final ConcurrentHashMap<String, ProjectReviewRuntimeSession> activeRuntimes;

    public PostDraftReviewAgentService(PostDraftReviewAgentReader reader,
                                       PostDraftReviewSessionFactory sessionFactory,
                                       PostDraftReviewProblemClassifier problemClassifier,
                                       PostDraftReviewStrategyResolver strategyResolver,
                                       PostDraftReviewProcessSummaryAssembler summaryAssembler,
                                       HumanInTheLoopGateway humanGateway,
                                       PostDraftReviewAgentWriter writer) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                summaryAssembler,
                humanGateway,
                writer,
                PostDraftReviewAgentTermWriter.noop(),
                null,
                ReviewSessionStore.noop(),
                ReviewRuntimeVisualizer.noop(),
                ProjectReviewRuntimePersistenceHook.noop(),
                PostDraftReviewBaselineStore.noop(),
                ReviewAgentPromptDumpWriter.disabled(),
                300
        );
    }

    public PostDraftReviewAgentService(PostDraftReviewAgentReader reader,
                                       PostDraftReviewSessionFactory sessionFactory,
                                       PostDraftReviewProblemClassifier problemClassifier,
                                       PostDraftReviewStrategyResolver strategyResolver,
                                       PostDraftReviewProcessSummaryAssembler summaryAssembler,
                                       HumanInTheLoopGateway humanGateway,
                                       PostDraftReviewAgentWriter writer,
                                       PostDraftRetranslationService retranslationService) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                summaryAssembler,
                humanGateway,
                writer,
                PostDraftReviewAgentTermWriter.noop(),
                null,
                ReviewSessionStore.noop(),
                ReviewRuntimeVisualizer.noop(),
                ProjectReviewRuntimePersistenceHook.noop(),
                PostDraftReviewBaselineStore.noop(),
                ReviewAgentPromptDumpWriter.disabled(),
                300
        );
    }

    public PostDraftReviewAgentService(PostDraftReviewAgentReader reader,
                                       PostDraftReviewSessionFactory sessionFactory,
                                       PostDraftReviewProblemClassifier problemClassifier,
                                       PostDraftReviewProcessSummaryAssembler summaryAssembler,
                                       HumanInTheLoopGateway humanGateway,
                                       PostDraftReviewAgentWriter writer,
                                       ReviewAgentStructuredGenerationPort generationPort) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                summaryAssembler,
                humanGateway,
                writer,
                PostDraftReviewAgentTermWriter.noop(),
                generationPort,
                ReviewSessionStore.noop(),
                ReviewRuntimeVisualizer.noop(),
                ProjectReviewRuntimePersistenceHook.noop(),
                PostDraftReviewBaselineStore.noop(),
                ReviewAgentPromptDumpWriter.disabled(),
                300
        );
    }

    public PostDraftReviewAgentService(PostDraftReviewAgentReader reader,
                                       PostDraftReviewSessionFactory sessionFactory,
                                       PostDraftReviewProblemClassifier problemClassifier,
                                       PostDraftReviewProcessSummaryAssembler summaryAssembler,
                                       HumanInTheLoopGateway humanGateway,
                                       PostDraftReviewAgentWriter writer,
                                       ReviewAgentStructuredGenerationPort generationPort,
                                       ReviewSessionStore reviewSessionStore) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                summaryAssembler,
                humanGateway,
                writer,
                PostDraftReviewAgentTermWriter.noop(),
                generationPort,
                reviewSessionStore,
                ReviewRuntimeVisualizer.noop(),
                ProjectReviewRuntimePersistenceHook.noop(),
                PostDraftReviewBaselineStore.noop(),
                ReviewAgentPromptDumpWriter.disabled(),
                300
        );
    }

    public PostDraftReviewAgentService(PostDraftReviewAgentReader reader,
                                       PostDraftReviewSessionFactory sessionFactory,
                                       PostDraftReviewProblemClassifier problemClassifier,
                                       PostDraftReviewProcessSummaryAssembler summaryAssembler,
                                       HumanInTheLoopGateway humanGateway,
                                       PostDraftReviewAgentWriter writer,
                                       ReviewAgentStructuredGenerationPort generationPort,
                                       ReviewSessionStore reviewSessionStore,
                                       ReviewRuntimeVisualizer runtimeVisualizer) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                summaryAssembler,
                humanGateway,
                writer,
                PostDraftReviewAgentTermWriter.noop(),
                generationPort,
                reviewSessionStore,
                runtimeVisualizer,
                ProjectReviewRuntimePersistenceHook.noop(),
                PostDraftReviewBaselineStore.noop(),
                ReviewAgentPromptDumpWriter.disabled(),
                300
        );
    }

    public PostDraftReviewAgentService(PostDraftReviewAgentReader reader,
                                       PostDraftReviewSessionFactory sessionFactory,
                                       PostDraftReviewProblemClassifier problemClassifier,
                                       PostDraftReviewProcessSummaryAssembler summaryAssembler,
                                       HumanInTheLoopGateway humanGateway,
                                       PostDraftReviewAgentWriter writer,
                                       PostDraftReviewAgentTermWriter termWriter,
                                       ReviewAgentStructuredGenerationPort generationPort,
                                       ReviewSessionStore reviewSessionStore,
                                       ReviewRuntimeVisualizer runtimeVisualizer) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                summaryAssembler,
                humanGateway,
                writer,
                termWriter,
                generationPort,
                reviewSessionStore,
                runtimeVisualizer,
                ProjectReviewRuntimePersistenceHook.noop(),
                PostDraftReviewBaselineStore.noop(),
                ReviewAgentPromptDumpWriter.disabled(),
                300
        );
    }

    public PostDraftReviewAgentService(PostDraftReviewAgentReader reader,
                                       PostDraftReviewSessionFactory sessionFactory,
                                       PostDraftReviewProblemClassifier problemClassifier,
                                       PostDraftReviewProcessSummaryAssembler summaryAssembler,
                                       HumanInTheLoopGateway humanGateway,
                                       PostDraftReviewAgentWriter writer,
                                       PostDraftReviewAgentTermWriter termWriter,
                                       ReviewAgentStructuredGenerationPort generationPort,
                                       ReviewSessionStore reviewSessionStore,
                                       ReviewRuntimeVisualizer runtimeVisualizer,
                                       ProjectReviewRuntimePersistenceHook persistenceHook) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                summaryAssembler,
                humanGateway,
                writer,
                termWriter,
                generationPort,
                reviewSessionStore,
                runtimeVisualizer,
                persistenceHook,
                PostDraftReviewBaselineStore.noop(),
                ReviewAgentPromptDumpWriter.disabled(),
                300
        );
    }

    public PostDraftReviewAgentService(PostDraftReviewAgentReader reader,
                                       PostDraftReviewSessionFactory sessionFactory,
                                       PostDraftReviewProblemClassifier problemClassifier,
                                       PostDraftReviewProcessSummaryAssembler summaryAssembler,
                                       HumanInTheLoopGateway humanGateway,
                                       PostDraftReviewAgentWriter writer,
                                       ReviewAgentStructuredGenerationPort generationPort,
                                       ReviewSessionStore reviewSessionStore,
                                       PostDraftReviewBaselineStore baselineStore) {
        this(
                reader,
                sessionFactory,
                problemClassifier,
                summaryAssembler,
                humanGateway,
                writer,
                PostDraftReviewAgentTermWriter.noop(),
                generationPort,
                reviewSessionStore,
                ReviewRuntimeVisualizer.noop(),
                ProjectReviewRuntimePersistenceHook.noop(),
                baselineStore,
                ReviewAgentPromptDumpWriter.disabled(),
                300
        );
    }

    public PostDraftReviewAgentService(PostDraftReviewAgentReader reader,
                                       PostDraftReviewSessionFactory sessionFactory,
                                       PostDraftReviewProblemClassifier problemClassifier,
                                       PostDraftReviewProcessSummaryAssembler summaryAssembler,
                                       HumanInTheLoopGateway humanGateway,
                                       PostDraftReviewAgentWriter writer,
                                       PostDraftReviewAgentTermWriter termWriter,
                                       ReviewAgentStructuredGenerationPort generationPort,
                                       ReviewSessionStore reviewSessionStore,
                                       ReviewRuntimeVisualizer runtimeVisualizer,
                                       ProjectReviewRuntimePersistenceHook persistenceHook,
                                       PostDraftReviewBaselineStore baselineStore,
                                       ReviewAgentPromptDumpWriter promptDumpWriter,
                                       long maxWallClockMinutes) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.problemClassifier = Objects.requireNonNull(problemClassifier, "problemClassifier");
        this.summaryAssembler = Objects.requireNonNull(summaryAssembler, "summaryAssembler");
        this.humanGateway = Objects.requireNonNull(humanGateway, "humanGateway");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.termWriter = Objects.requireNonNull(termWriter, "termWriter");
        this.projectOutputAssembler = new ProjectReviewOutputAssembler();
        this.reviewSessionStore = Objects.requireNonNull(reviewSessionStore, "reviewSessionStore");
        this.persistenceHook = Objects.requireNonNull(persistenceHook, "persistenceHook");
        this.baselineStore = Objects.requireNonNull(baselineStore, "baselineStore");
        this.promptDumpWriter = Objects.requireNonNull(promptDumpWriter, "promptDumpWriter");
        this.activeRuntimes = new ConcurrentHashMap<>();
        this.autonomousAgent = generationPort == null ? null : buildAutonomousAgent(generationPort, runtimeVisualizer, maxWallClockMinutes);
    }

    public PostDraftReviewAgentResult review(StartPostDraftReviewAgentCommand command) {
        Objects.requireNonNull(command, "command");
        requireAutonomousAgent();

        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize(
                command.projectId(),
                List.of(command.focus().chunkId())
        );
        activeRuntimes.put(runtime.projectId(), runtime);
        ProjectReviewRuntimeSession finalRuntime = autonomousAgent.run(runtime, command.operatorNote());
        finalRuntime = submitHumanRequestIfPresent(finalRuntime);
        clearActiveRuntimeIfStopped(finalRuntime);
        PostDraftReviewAgentResult projectResult = projectOutputAssembler.assemble(finalRuntime);
        if (projectResult.humanReviewRequest().isPresent()) {
            return writer.writeHumanRequired(projectResult.humanReviewRequest().orElseThrow());
        }
        return writer.writeCompleted(projectResult.finalMergedTranslatedText(), projectResult.processSummary());
    }

    public PostDraftReviewAgentResult reviewProject(StartProjectPostDraftReviewAgentCommand command) {
        Objects.requireNonNull(command, "command");
        requireAutonomousAgent();

        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize(
                command.projectId(),
                reader.listChunkIdsByProject(command.projectId())
        );
        activeRuntimes.put(runtime.projectId(), runtime);
        ProjectReviewRuntimeSession finalRuntime = autonomousAgent.run(runtime, command.operatorNote());
        finalRuntime = submitHumanRequestIfPresent(finalRuntime);
        clearActiveRuntimeIfStopped(finalRuntime);
        return projectOutputAssembler.assemble(finalRuntime);
    }

    public PostDraftReviewAgentResult resumeProject(String projectId,
                                                    String humanReviewNote) {
        Objects.requireNonNull(projectId, "projectId");
        requireAutonomousAgent();

        StoredReviewSession stored = reviewSessionStore.load(projectId)
                .orElseThrow(() -> new IllegalStateException("Stored review session not found for projectId=" + projectId));
        activeRuntimes.put(projectId, stored.runtime());
        ProjectReviewRuntimeSession finalRuntime = autonomousAgent.resume(stored.runtime(), humanReviewNote);
        finalRuntime = submitHumanRequestIfPresent(finalRuntime);
        clearActiveRuntimeIfStopped(finalRuntime);
        if (finalRuntime.status() != io.quillloom.application.postdraft.review.model.ProjectReviewStatus.WAITING_HUMAN) {
            reviewSessionStore.delete(projectId);
        }
        return projectOutputAssembler.assemble(finalRuntime);
    }

    public Optional<ProjectReviewRuntimeSession> findActiveRuntime(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return Optional.ofNullable(activeRuntimes.get(projectId));
    }

    public void resetProject(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        writer.resetProjectRevisions(projectId);
        reviewSessionStore.delete(projectId);
        activeRuntimes.remove(projectId);
    }

    public void createProjectReviewBaseline(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        baselineStore.createBaseline(projectId);
    }

    public void resetProjectFromBaseline(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        baselineStore.restoreBaseline(projectId);
        reviewSessionStore.delete(projectId);
        activeRuntimes.remove(projectId);
    }

    private ProjectReviewRuntimeSession submitHumanRequestIfPresent(ProjectReviewRuntimeSession runtime) {
        if (runtime.humanReviewRequest().isEmpty()) {
            return runtime;
        }
        HumanReviewRequest submittedRequest = humanGateway.submit(runtime.humanReviewRequest().orElseThrow());
        return runtime.replaceHumanReviewRequest(submittedRequest);
    }

    private void requireAutonomousAgent() {
        if (autonomousAgent == null) {
            throw new IllegalStateException("ReviewAgentStructuredGenerationPort is not configured");
        }
    }

    private void clearActiveRuntimeIfStopped(ProjectReviewRuntimeSession runtime) {
        if (runtime.status() != io.quillloom.application.postdraft.review.model.ProjectReviewStatus.ACTIVE) {
            activeRuntimes.remove(runtime.projectId());
        }
    }

    private AutonomousProjectReviewAgent buildAutonomousAgent(ReviewAgentStructuredGenerationPort generationPort,
                                                              ReviewRuntimeVisualizer runtimeVisualizer,
                                                              long maxWallClockMinutes) {
        ReviewToolRegistry toolRegistry = ReviewToolRegistry.defaultRegistry();
        PostDraftRevisionService revisionService = new PostDraftRevisionService(
                new PromptBackedRevisionDraftProvider(new RevisionPromptBuilder(), generationPort),
                new LlmBackedRevisionSelfCheckService(new RevisionSelfCheckPromptBuilder(), generationPort)
        );
        return new AutonomousProjectReviewAgent(
                reader,
                sessionFactory,
                problemClassifier,
                new SequenceProjectFocusSelector(),
                new PromptBackedNextStepDecisionProvider(
                        new InvestigationPromptBuilder(),
                        toolRegistry,
                        generationPort,
                        promptDumpWriter
                ),
                new ReviewToolExecutor(
                        toolRegistry,
                        new ReviewToolGuardrail(),
                        reader,
                        termWriter,
                        new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), generationPort),
                        revisionService,
                        new WorkingSetCompletionHandler(reader, summaryAssembler),
                        summaryAssembler,
                        new FocusHumanStopPolicy(1, 1)
                ),
                Objects.requireNonNull(runtimeVisualizer, "runtimeVisualizer"),
                trackingPersistenceHook(),
                io.quillloom.application.postdraft.review.model.ReviewAgentConfig.defaultConfig(),
                maxWallClockMinutes
        );
    }

    private ProjectReviewRuntimePersistenceHook trackingPersistenceHook() {
        return (previousRuntime, currentRuntime) -> {
            activeRuntimes.put(currentRuntime.projectId(), currentRuntime);
            persistenceHook.afterTransition(previousRuntime, currentRuntime);
            clearActiveRuntimeIfStopped(currentRuntime);
        };
    }
}
