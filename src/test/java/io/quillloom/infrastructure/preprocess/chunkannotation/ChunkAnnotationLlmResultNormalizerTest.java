package io.quillloom.infrastructure.preprocess.chunkannotation;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.PersonAliasHint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChunkAnnotationLlmResultNormalizerTest {

    @Test
    void shouldFallbackSummaryAndNormalizeNullableCollections() {
        ChunkAnnotationLlmResultNormalizer normalizer = new ChunkAnnotationLlmResultNormalizer();
        ChunkAnnotationTaskInput input = createTaskInput();

        ChunkAnnotationLlmResult normalized = normalizer.normalize(
                input,
                new ChunkAnnotationLlmResult("   ", null, null, null, null, null)
        );

        assertFalse(normalized.summary().isBlank());
        assertEquals(List.of(), normalized.entities());
        assertEquals(List.of(), normalized.backgroundQuestions());
        assertEquals(List.of(), normalized.translationRisks());
        assertEquals(List.of(), normalized.keyExpressions());
        assertEquals(List.of(), normalized.personAliasHints());
    }

    @Test
    void shouldTrimDeduplicateAndCapEntities() {
        ChunkAnnotationLlmResultNormalizer normalizer = new ChunkAnnotationLlmResultNormalizer();
        ChunkAnnotationTaskInput input = createTaskInput();

        ChunkAnnotationLlmResult normalized = normalizer.normalize(
                input,
                new ChunkAnnotationLlmResult(
                        " 摘要 ",
                        Arrays.asList(" Alice ", "Bob", "Alice", " ", null, "Paris", "Bell", "House", "River", "Bridge", "Street", "Rain", "Window", "Extra"),
                        Arrays.asList(" 问题一 ", "问题一", " "),
                        Arrays.asList(" 风险一 ", null),
                        List.of(" 关键表达 "),
                        List.of(
                                new PersonAliasHint(List.of(" Bowling ", "le Capitaine", "Bowling"), "same-person-name-variant", "HIGH", " 同段交替出现 "),
                                new PersonAliasHint(List.of(), "same-person-name-variant", "LOW", "ignored")
                        )
                )
        );

        assertEquals("摘要", normalized.summary());
        assertEquals(
                List.of("Alice", "Bob", "Paris", "Bell", "House", "River", "Bridge", "Street", "Rain", "Window", "Extra"),
                normalized.entities()
        );
        assertEquals(List.of("问题一"), normalized.backgroundQuestions());
        assertEquals(List.of("风险一"), normalized.translationRisks());
        assertEquals(List.of("关键表达"), normalized.keyExpressions());
        assertEquals(1, normalized.personAliasHints().size());
        assertEquals(List.of("Bowling", "le Capitaine"), normalized.personAliasHints().get(0).surfaceForms());
    }

    private ChunkAnnotationTaskInput createTaskInput() {
        PreprocessBookCommand command = new PreprocessBookCommand("project-1", "示例小说", "Alice met Bob in Paris near the river.", "en", "zh");
        return new ChunkAnnotationTaskInput(
                command.projectId(),
                command.title(),
                command.sourceLanguage(),
                command.targetLanguage(),
                new BookAnalysis("全书概要", "叙事结构", "冷静克制", List.of(), List.of()),
                List.of(new GlobalConstraint("c1", "style", "保持一致")),
                new ChunkDescriptor("chunk-1", 1, 0, command.sourceText().length(), command.sourceText())
        );
    }
}
