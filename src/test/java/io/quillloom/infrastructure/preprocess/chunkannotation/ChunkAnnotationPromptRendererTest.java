package io.quillloom.infrastructure.preprocess.chunkannotation;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkAnnotationPromptRendererTest {

    @Test
    void shouldRenderStructuredAnnotationPrompt() {
        String prompt = new ChunkAnnotationPromptRenderer().render(createTaskInput());

        assertTrue(prompt.contains("chunk 标注助手"));
        assertTrue(prompt.contains("JSON 必须严格包含以下字段"));
        assertTrue(prompt.contains("summary、entities、backgroundQuestions、translationRisks、keyExpressions、personAliasHints"));
        assertTrue(prompt.contains("chunkId：chunk-1"));
        assertTrue(prompt.contains("Alice met Bob in Paris near the river."));
    }

    @Test
    void shouldConstrainFieldLengthAndRiskCount() {
        String prompt = new ChunkAnnotationPromptRenderer().render(createTaskInput());

        assertTrue(prompt.contains("只写 1 句"));
        assertTrue(prompt.contains("最多 3 项"));
        assertTrue(prompt.contains("每项只写 1 条风险点"));
        assertTrue(prompt.contains("禁止把多条理由塞进同一项"));
        assertTrue(prompt.contains("避免把一整段翻译分析塞进 summary 或 translationRisks"));
    }

    @Test
    void shouldAskForBroadEntityCoverageAndTranslationConsistencyRisks() {
        String prompt = new ChunkAnnotationPromptRenderer().render(createTaskInput());

        assertTrue(prompt.contains("尽量覆盖人名、地名、场所名、店名、机构名、称谓和反复出现的专名"));
        assertTrue(prompt.contains("缺少稳定译名"));
        assertTrue(prompt.contains("需要统一译名"));
        assertTrue(prompt.contains("不要因为暂时无法确认译名就漏掉实体"));
    }

    private ChunkAnnotationTaskInput createTaskInput() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-1",
                "示例小说",
                "Alice met Bob in Paris near the river.",
                "en",
                "zh"
        );
        return new ChunkAnnotationTaskInput(
                command.projectId(),
                command.title(),
                command.sourceLanguage(),
                command.targetLanguage(),
                new BookAnalysis("全书摘要", "叙事结构", "冷静克制", List.of(), List.of()),
                List.of(new GlobalConstraint("c1", "style", "保持术语和人名译法一致")),
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, command.sourceText().length(), command.sourceText())
        );
    }
}
