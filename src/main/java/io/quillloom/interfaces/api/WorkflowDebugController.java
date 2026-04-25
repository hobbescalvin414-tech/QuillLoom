package io.quillloom.interfaces.api;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.workflow.service.NovelTranslationWorkflowService;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.DraftCompilation;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.interfaces.api.dto.DraftCompilationRequest;
import io.quillloom.interfaces.api.dto.DraftCompilationResponse;
import io.quillloom.interfaces.api.dto.WorkflowDraftRunRequest;
import io.quillloom.interfaces.api.dto.WorkflowDraftRunResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/debug/workflow")
public class WorkflowDebugController {

    private final NovelTranslationWorkflowService workflowService;

    public WorkflowDebugController(NovelTranslationWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/draft")
    public WorkflowDraftRunResponse draft(@Valid @RequestBody WorkflowDraftRunRequest request) {
        PreprocessBookCommand command = new PreprocessBookCommand(
                request.projectId(),
                request.title(),
                request.sourceText(),
                request.sourceLanguage(),
                request.targetLanguage()
        );
        var state = workflowService.runDraftWorkflow(
                command,
                new ProjectMemorySnapshot(request.projectId(), Map.of(), List.of(), List.of()),
                new ChapterMemorySnapshot(resolveChapterId(request), Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );
        return WorkflowDraftRunResponse.from(state);
    }

    @PostMapping("/compile-drafts")
    public DraftCompilationResponse compileDrafts(@Valid @RequestBody DraftCompilationRequest request) {
        DraftCompilation compilation = workflowService.compileDrafts(
                request.projectId(),
                request.chunkDrafts().stream()
                        .map(this::toChunkTranslationDraft)
                        .toList()
        );
        return DraftCompilationResponse.from(compilation);
    }

    private String resolveChapterId(WorkflowDraftRunRequest request) {
        if (request.chapterId() != null && !request.chapterId().isBlank()) {
            return request.chapterId();
        }
        return request.projectId() + "-chapter-1";
    }

    private ChunkTranslationDraft toChunkTranslationDraft(DraftCompilationRequest.ChunkDraftBlock block) {
        return new ChunkTranslationDraft(
                block.chunkId(),
                block.translatedText(),
                "",
                List.of(),
                Map.of(),
                List.of(),
                new ChunkTransitionNote("", "", false)
        );
    }
}
