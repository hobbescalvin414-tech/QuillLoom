package io.quillloom.application.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler;
import io.quillloom.application.postdraft.assembler.PostDraftReviewPackageAssembler;
import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.application.preprocess.service.PreprocessApplicationService;
import io.quillloom.application.translation.assembler.DraftCompilationAssembler;
import io.quillloom.application.translation.model.TranslationDraftRunResult;
import io.quillloom.application.translation.service.TranslationApplicationService;
import io.quillloom.application.workflow.progress.WorkflowConsoleProgressReporter;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.DraftCompilation;
import io.quillloom.domain.translation.DraftCompilationInput;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.domain.workflow.NovelTranslationWorkflowState;
import io.quillloom.infrastructure.postdraft.InMemoryPostDraftReviewPackageRepository;
import io.quillloom.infrastructure.preprocess.InMemoryProjectKnowledgeBaseRepository;
import io.quillloom.infrastructure.workflow.trace.WorkflowDraftArtifactWriter;
import io.quillloom.infrastructure.workflow.trace.WorkflowTraceArtifactWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class NovelTranslationWorkflowService {

    private static final DateTimeFormatter RUN_ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PreprocessApplicationService preprocessApplicationService;
    private final TranslationApplicationService translationApplicationService;
    private final DraftCompilationAssembler draftCompilationAssembler;
    private final PostDraftReviewPackageAssembler postDraftReviewPackageAssembler;
    private final PostDraftContinuationContextAssembler postDraftContinuationContextAssembler;
    private final ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository;
    private final PostDraftReviewPackageRepository postDraftReviewPackageRepository;
    private final WorkflowTraceRecorder traceRecorder;
    private final WorkflowTraceArtifactWriter traceArtifactWriter;
    private final WorkflowDraftArtifactWriter draftArtifactWriter;

    public NovelTranslationWorkflowService(PreprocessApplicationService preprocessApplicationService,
                                           TranslationApplicationService translationApplicationService,
                                           DraftCompilationAssembler draftCompilationAssembler) {
        this(
                preprocessApplicationService,
                translationApplicationService,
                draftCompilationAssembler,
                new PostDraftReviewPackageAssembler(),
                new PostDraftContinuationContextAssembler(),
                new InMemoryProjectKnowledgeBaseRepository(),
                new InMemoryPostDraftReviewPackageRepository()
        );
    }

    @Autowired
    public NovelTranslationWorkflowService(PreprocessApplicationService preprocessApplicationService,
                                           TranslationApplicationService translationApplicationService,
                                           DraftCompilationAssembler draftCompilationAssembler,
                                           PostDraftReviewPackageAssembler postDraftReviewPackageAssembler,
                                           PostDraftContinuationContextAssembler postDraftContinuationContextAssembler,
                                           ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository,
                                           PostDraftReviewPackageRepository postDraftReviewPackageRepository) {
        this.preprocessApplicationService = preprocessApplicationService;
        this.translationApplicationService = translationApplicationService;
        this.draftCompilationAssembler = draftCompilationAssembler;
        this.postDraftReviewPackageAssembler = postDraftReviewPackageAssembler;
        this.postDraftContinuationContextAssembler = postDraftContinuationContextAssembler;
        this.projectKnowledgeBaseRepository = projectKnowledgeBaseRepository;
        this.postDraftReviewPackageRepository = postDraftReviewPackageRepository;
        this.traceRecorder = new WorkflowTraceRecorder();
        this.traceArtifactWriter = new WorkflowTraceArtifactWriter(new ObjectMapper(), Path.of("run-output", "workflow-trace"));
        this.draftArtifactWriter = new WorkflowDraftArtifactWriter(Path.of("run-output", "book-sample"));
    }

    NovelTranslationWorkflowService(PreprocessApplicationService preprocessApplicationService,
                                    TranslationApplicationService translationApplicationService,
                                    DraftCompilationAssembler draftCompilationAssembler,
                                    PostDraftReviewPackageAssembler postDraftReviewPackageAssembler,
                                    PostDraftContinuationContextAssembler postDraftContinuationContextAssembler,
                                    ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository,
                                    PostDraftReviewPackageRepository postDraftReviewPackageRepository,
                                    WorkflowTraceRecorder traceRecorder,
                                    WorkflowTraceArtifactWriter traceArtifactWriter,
                                    WorkflowDraftArtifactWriter draftArtifactWriter) {
        this.preprocessApplicationService = preprocessApplicationService;
        this.translationApplicationService = translationApplicationService;
        this.draftCompilationAssembler = draftCompilationAssembler;
        this.postDraftReviewPackageAssembler = postDraftReviewPackageAssembler;
        this.postDraftContinuationContextAssembler = postDraftContinuationContextAssembler;
        this.projectKnowledgeBaseRepository = projectKnowledgeBaseRepository;
        this.postDraftReviewPackageRepository = postDraftReviewPackageRepository;
        this.traceRecorder = traceRecorder;
        this.traceArtifactWriter = traceArtifactWriter;
        this.draftArtifactWriter = draftArtifactWriter;
    }

    public PreprocessDossier runPreprocess(PreprocessBookCommand command) {
        return preprocessApplicationService.preprocess(command);
    }

    public NovelTranslationWorkflowState start(String projectId) {
        return NovelTranslationWorkflowState.initialized(projectId);
    }

    public NovelTranslationWorkflowState runPreprocess(PreprocessBookCommand command,
                                                       NovelTranslationWorkflowState state) {
        PreprocessDossier dossier = preprocessApplicationService.preprocess(command);
        return state.advanceToPreprocessed(dossier);
    }

    public ChunkTranslationDraft translateChunk(PreprocessDossier dossier,
                                                ChunkAnnotation chunk,
                                                ProjectMemorySnapshot projectMemory,
                                                ChapterMemorySnapshot chapterMemory,
                                                List<ChunkTranslationDraft> completedDrafts,
                                                TranslationRuntimeOptions runtimeOptions) {
        return translationApplicationService.translateChunk(dossier, chunk, projectMemory, chapterMemory, completedDrafts, runtimeOptions);
    }

    public List<ChunkTranslationDraft> translateChunks(PreprocessDossier dossier,
                                                       ProjectMemorySnapshot projectMemory,
                                                       ChapterMemorySnapshot chapterMemory,
                                                       TranslationRuntimeOptions runtimeOptions) {
        return translationApplicationService.translateChunks(dossier, projectMemory, chapterMemory, runtimeOptions);
    }

    public NovelTranslationWorkflowState draftAllChunks(NovelTranslationWorkflowState state,
                                                        ProjectMemorySnapshot projectMemory,
                                                        ChapterMemorySnapshot chapterMemory,
                                                        TranslationRuntimeOptions runtimeOptions) {
        if (state.preprocessDossier() == null) {
            throw new IllegalStateException("Draft stage requires a preprocessed dossier.");
        }
        TranslationDraftRunResult result = translationApplicationService.translateChunksWithMemory(
                state.preprocessDossier(),
                projectMemory,
                chapterMemory,
                runtimeOptions
        );
        return state.advanceToDrafted(result.drafts(), result.finalProjectMemory());
    }

    public NovelTranslationWorkflowState recordChunkDrafts(NovelTranslationWorkflowState state,
                                                           List<ChunkTranslationDraft> chunkDrafts) {
        return state.advanceToDrafted(chunkDrafts);
    }

    public DraftCompilation compileDrafts(String projectId,
                                          List<ChunkTranslationDraft> chunkDrafts) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("Project id must not be blank.");
        }
        if (chunkDrafts == null || chunkDrafts.isEmpty()) {
            throw new IllegalArgumentException("Chunk drafts must not be empty.");
        }
        return draftCompilationAssembler.assemble(new DraftCompilationInput(projectId, chunkDrafts));
    }

    public NovelTranslationWorkflowState compileDrafts(NovelTranslationWorkflowState state) {
        return state.advanceToCompiled(compileDrafts(state.projectId(), state.chunkDrafts()));
    }

    public PostDraftReviewPackage savePostDraftReviewPackage(NovelTranslationWorkflowState state,
                                                             ProjectMemorySnapshot projectMemory) {
        if (state == null || state.preprocessDossier() == null) {
            throw new IllegalArgumentException("Workflow state must contain a preprocess dossier.");
        }
        if (state.chunkDrafts() == null || state.chunkDrafts().isEmpty()) {
            throw new IllegalArgumentException("Workflow state must contain chunk drafts.");
        }
        PostDraftReviewPackage reviewPackage = postDraftReviewPackageAssembler.assemble(
                state.preprocessDossier(),
                state.chunkDrafts(),
                projectMemory,
                state.draftCompilation()
        );
        postDraftReviewPackageRepository.save(reviewPackage);
        return reviewPackage;
    }

    public PostDraftContinuationContext loadPostDraftContinuationContext(String projectId) {
        PostDraftReviewPackage reviewPackage = postDraftReviewPackageRepository.load(projectId)
                .orElseThrow(() -> new IllegalArgumentException("No post-draft review package found for projectId=" + projectId));
        return postDraftContinuationContextAssembler.assemble(
                reviewPackage,
                projectKnowledgeBaseRepository.load(projectId).orElse(io.quillloom.domain.knowledge.ProjectKnowledgeBase.empty(projectId))
        );
    }

    public NovelTranslationWorkflowState runDraftWorkflow(PreprocessBookCommand command,
                                                          ProjectMemorySnapshot projectMemory,
                                                          ChapterMemorySnapshot chapterMemory,
                                                          TranslationRuntimeOptions runtimeOptions) {
        String runId = RUN_ID_FORMATTER.format(LocalDateTime.now()) + "-" + command.projectId();
        WorkflowConsoleProgressReporter progressReporter = new WorkflowConsoleProgressReporter();
        traceRecorder.startRun(runId, "draft-workflow", command.projectId(), List.of(progressReporter));
        try {
            NovelTranslationWorkflowState state = start(command.projectId());
            state = runPreprocess(command, state);
            state = draftAllChunks(state, projectMemory, chapterMemory, runtimeOptions);
            state = compileDrafts(state);
            if (state.finalProjectMemory() == null) {
                throw new IllegalStateException("final_project_memory_missing: projectId=" + state.projectId());
            }
            savePostDraftReviewPackage(state, state.finalProjectMemory());
            flushDraftArtifacts(runId, command, state);
            flushTraceArtifacts();
            traceRecorder.completeRun();
            return state;
        } catch (RuntimeException exception) {
            try {
                traceRecorder.failRun(exception);
                flushTraceArtifacts();
            } catch (RuntimeException flushException) {
                exception.addSuppressed(flushException);
            }
            throw exception;
        } finally {
            progressReporter.close();
            traceRecorder.clear();
        }
    }

    private void flushTraceArtifacts() {
        traceRecorder.currentSession().ifPresent(session -> {
            try {
                traceArtifactWriter.write(session);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write workflow trace artifacts.", exception);
            }
        });
    }

    private void flushDraftArtifacts(String runId,
                                     PreprocessBookCommand command,
                                     NovelTranslationWorkflowState state) {
        try {
            draftArtifactWriter.write(runId, command, state);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write workflow draft artifacts.", exception);
        }
    }
}
