package io.quillloom.infrastructure.translation;

import io.quillloom.domain.book.BookProject;
import io.quillloom.domain.memory.CoarseBlockContext;
import io.quillloom.domain.memory.ExecutionContextView;
import io.quillloom.domain.memory.LocalSourceContext;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.PersonAliasHint;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.domain.translation.TranslationSourceMaterial;
import io.quillloom.domain.translation.TranslationTaskInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmChunkTranslatorTest {

    @Test
    void shouldSendDetectedTextIssuesToRevisionRoundInsteadOfFailingFast() {
        FakeClient client = new FakeClient(
                new ChunkTranslationLlmResult(
                        "孔代咖啡馆（Le Conde）——巴黎左岸一家边缘文化据点——",
                        "commentary",
                        List.of(),
                        List.of(),
                        List.of(),
                        new ChunkTranslationTransitionNoteResult("", "", false)
                ),
                new ChunkTranslationLlmResult(
                        "孔代咖啡馆",
                        "commentary",
                        List.of(),
                        List.of(),
                        List.of(),
                        new ChunkTranslationTransitionNoteResult("", "", false)
                )
        );
        LlmChunkTranslator translator = new LlmChunkTranslator(
                new TranslationPromptRenderer(new TranslationPromptProperties()),
                client,
                new ChunkTranslationLlmResultNormalizer(),
                new ChunkTranslationResultValidator(),
                new ChunkTranslationLlmResultParser()
        );

        ChunkTranslationDraft draft = translator.translate(createInput());

        assertEquals("孔代咖啡馆", draft.translatedText());
        assertEquals(2, client.prompts().size());
        assertTrue(client.prompts().get(1).contains("【正文问题清单】"));
        assertTrue(client.prompts().get(1).contains("encyclopedic-insertion"));
    }

    @Test
    void shouldFallbackOnlyForRecoverableRevisionStructuredOutputException() {
        FakeClient client = new FakeClient(
                textBoundaryIssueResult(),
                new ChunkTranslationStructuredOutputException("revision structured output invalid")
        );
        LlmChunkTranslator translator = translator(client);

        ChunkTranslationDraft draft = translator.translate(createInput());

        assertEquals("孔代咖啡馆（Le Conde）——巴黎左岸一家边缘文化据点——", draft.translatedText());
        assertTrue(draft.decisionNotes().stream()
                .anyMatch(note -> note.type().equals("revision-round-fallback")));
    }

    @Test
    void shouldRethrowRevisionNullPointerException() {
        FakeClient client = new FakeClient(
                textBoundaryIssueResult(),
                new NullPointerException("bug")
        );
        LlmChunkTranslator translator = translator(client);

        assertThrows(NullPointerException.class, () -> translator.translate(createInput()));
    }

    @Test
    void shouldRethrowRevisionIllegalStateException() {
        FakeClient client = new FakeClient(
                textBoundaryIssueResult(),
                new IllegalStateException("validator contract failed")
        );
        LlmChunkTranslator translator = translator(client);

        assertThrows(IllegalStateException.class, () -> translator.translate(createInput()));
    }

    @Test
    void shouldRethrowRevisionTransientLlmException() {
        FakeClient client = new FakeClient(
                textBoundaryIssueResult(),
                new ChunkTranslationTransientException("429 rate limited")
        );
        LlmChunkTranslator translator = translator(client);

        assertThrows(ChunkTranslationTransientException.class, () -> translator.translate(createInput()));
    }

    private LlmChunkTranslator translator(FakeClient client) {
        return new LlmChunkTranslator(
                new TranslationPromptRenderer(new TranslationPromptProperties()),
                client,
                new ChunkTranslationLlmResultNormalizer(),
                new ChunkTranslationResultValidator(),
                new ChunkTranslationLlmResultParser()
        );
    }

    private ChunkTranslationLlmResult textBoundaryIssueResult() {
        return new ChunkTranslationLlmResult(
                "孔代咖啡馆（Le Conde）——巴黎左岸一家边缘文化据点——",
                "commentary",
                List.of(),
                List.of(),
                List.of(),
                new ChunkTranslationTransitionNoteResult("", "", false)
        );
    }

    private TranslationTaskInput createInput() {
        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, 20, "Bowling entered Le Conde."),
                "摘要",
                List.of("Bowling", "Le Condé"),
                List.of(),
                List.of(),
                List.of("Bowling entered"),
                List.of(new PersonAliasHint(
                        List.of("Bowling", "le Capitaine"),
                        "same-person-name-variant",
                        "HIGH",
                        "同段交替出现"
                ))
        );

        TranslationSourceMaterial sourceMaterial = new TranslationSourceMaterial(
                new BookProject("project-1", "sample", "en", "zh"),
                new BookAnalysis("全书概要", "叙事结构", "冷静克制", List.of(), List.of()),
                chunk
        );

        ExecutionContextView executionContextView = new ExecutionContextView(
                Map.of("Bowling", "鲍林"),
                List.of(),
                LocalSourceContext.empty(),
                CoarseBlockContext.empty(),
                List.of(),
                List.of(),
                List.of()
        );

        return new TranslationTaskInput(sourceMaterial, executionContextView, TranslationRuntimeOptions.defaults());
    }

    private static final class FakeClient implements LlmChunkTranslationClient {
        private final List<Object> responses;
        private final List<String> prompts = new ArrayList<>();
        private int index = 0;

        private FakeClient(Object... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ChunkTranslationLlmResult generate(String prompt) {
            prompts.add(prompt);
            Object response = responses.get(index++);
            if (response instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return (ChunkTranslationLlmResult) response;
        }

        private List<String> prompts() {
            return prompts;
        }
    }
}
