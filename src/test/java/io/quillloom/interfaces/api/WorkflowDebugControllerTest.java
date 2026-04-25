package io.quillloom.interfaces.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.workflow.service.NovelTranslationWorkflowService;
import io.quillloom.domain.book.BookProject;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.KnowledgeEnrichmentBundle;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.DraftCompilation;
import io.quillloom.domain.workflow.NovelTranslationWorkflowState;
import io.quillloom.domain.workflow.TranslationWorkflowStage;
import io.quillloom.interfaces.api.dto.WorkflowDraftRunResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowDebugController.class)
class WorkflowDebugControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NovelTranslationWorkflowService workflowService;

    @Test
    void shouldRunDraftWorkflowEndpoint() throws Exception {
        when(workflowService.runDraftWorkflow(any(), any(), any(), any()))
                .thenReturn(sampleCompiledState());

        mockMvc.perform(post("/api/debug/workflow/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "project-1",
                                  "title": "sample-novel",
                                  "sourceText": "Alice met Bob in Paris.",
                                  "sourceLanguage": "en",
                                  "targetLanguage": "zh"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("project-1"))
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.mergedDraft").value("draft-text"))
                .andExpect(jsonPath("$.activeGlossary.Alice").value("爱丽丝"))
                .andExpect(jsonPath("$.candidateGlossary[0].sourceTerm").value("Bob"))
                .andExpect(jsonPath("$.chunkDrafts[0].chunkId").value("chunk-1"))
                .andExpect(jsonPath("$.chunkDrafts[0].translatedText").value("draft-text"));
    }

    @Test
    void shouldCompileDraftBlocksEndpoint() throws Exception {
        when(workflowService.compileDrafts(anyString(), anyList()))
                .thenReturn(new DraftCompilation(
                        "project-1",
                        List.of(new ChunkTranslationDraft(
                                "chunk-1",
                                "block-one",
                                "",
                                List.of(),
                                Map.of(),
                                List.of(),
                                new ChunkTransitionNote("", "", false)
                        )),
                        "block-one",
                        List.of()
                ));

        mockMvc.perform(post("/api/debug/workflow/compile-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "projectId", "project-1",
                                "chunkDrafts", List.of(Map.of(
                                        "chunkId", "chunk-1",
                                        "translatedText", "block-one"
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("project-1"))
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.mergedDraft").value("block-one"));
    }

    @Test
    void shouldFailWhenWorkflowDraftResponseActiveGlossaryHasConfirmedTermConflict() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> WorkflowDraftRunResponse.from(sampleCompiledStateWithTermConflict())
        );

        assertTrue(exception.getMessage().contains("confirmed_term_conflict"));
        assertTrue(exception.getMessage().contains("sourceTerm=Le Condé"));
        assertTrue(exception.getMessage().contains("existing=孔代咖啡馆"));
        assertTrue(exception.getMessage().contains("incoming=勒孔代咖啡馆"));
        assertTrue(exception.getMessage().contains("chunkId=chunk-2"));
    }

    private NovelTranslationWorkflowState sampleCompiledState() {
        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, 0, 24, "Alice met Bob in Paris."),
                "meeting scene",
                List.of("Alice", "Bob", "Paris"),
                List.of(),
                List.of(),
                List.of()
        );
        PreprocessDossier dossier = new PreprocessDossier(
                new BookProject("project-1", "sample-novel", "en", "zh"),
                new GlobalAnalysisBundle(new BookAnalysis("meeting scene", "", "", List.of(), List.of()), List.of()),
                new ChunkAnnotationBundle(List.of(chunk)),
                new KnowledgeEnrichmentBundle(ProjectKnowledgeBase.empty("project-1"))
        );
        ChunkTranslationDraft draft = new ChunkTranslationDraft(
                "chunk-1",
                "draft-text",
                "keep names consistent",
                List.of(),
                Map.of("Alice", "爱丽丝"),
                List.of(new io.quillloom.domain.translation.TranslationCandidateUpdate("Bob", "鲍勃", "common transliteration", true)),
                new ChunkTransitionNote("", "", false)
        );
        DraftCompilation compilation = new DraftCompilation(
                "project-1",
                List.of(draft),
                "draft-text",
                List.of()
        );
        return new NovelTranslationWorkflowState(
                "project-1",
                TranslationWorkflowStage.COMPILED,
                dossier,
                List.of(draft),
                compilation
        );
    }

    private NovelTranslationWorkflowState sampleCompiledStateWithTermConflict() {
        ChunkAnnotation firstChunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, 0, 24, "Le Condé appeared."),
                "first scene",
                List.of("Le Condé"),
                List.of(),
                List.of(),
                List.of()
        );
        ChunkAnnotation secondChunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-2", 2, 25, 49, "Le Condé returned."),
                "second scene",
                List.of("Le Condé"),
                List.of(),
                List.of(),
                List.of()
        );
        PreprocessDossier dossier = new PreprocessDossier(
                new BookProject("project-conflict", "sample-novel", "fr", "zh"),
                new GlobalAnalysisBundle(new BookAnalysis("meeting scene", "", "", List.of(), List.of()), List.of()),
                new ChunkAnnotationBundle(List.of(firstChunk, secondChunk)),
                new KnowledgeEnrichmentBundle(ProjectKnowledgeBase.empty("project-conflict"))
        );
        ChunkTranslationDraft firstDraft = new ChunkTranslationDraft(
                "chunk-1",
                "draft-1",
                "first term",
                List.of(),
                Map.of("Le Condé", "孔代咖啡馆"),
                List.of(),
                new ChunkTransitionNote("", "", false)
        );
        ChunkTranslationDraft secondDraft = new ChunkTranslationDraft(
                "chunk-2",
                "draft-2",
                "conflicting term",
                List.of(),
                Map.of("Le Condé", "勒孔代咖啡馆"),
                List.of(),
                new ChunkTransitionNote("", "", false)
        );
        DraftCompilation compilation = new DraftCompilation(
                "project-conflict",
                List.of(firstDraft, secondDraft),
                "draft-1\n\ndraft-2",
                List.of()
        );
        return new NovelTranslationWorkflowState(
                "project-conflict",
                TranslationWorkflowStage.COMPILED,
                dossier,
                List.of(firstDraft, secondDraft),
                compilation
        );
    }
}
