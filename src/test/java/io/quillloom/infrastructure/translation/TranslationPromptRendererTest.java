package io.quillloom.infrastructure.translation;

import io.quillloom.domain.book.BookProject;
import io.quillloom.domain.memory.CoarseBlockContext;
import io.quillloom.domain.memory.ExecutionContextView;
import io.quillloom.domain.memory.LocalSourceContext;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.PersonAliasHint;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.domain.translation.TranslationSourceMaterial;
import io.quillloom.domain.translation.TranslationTaskInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationPromptRendererTest {

    @Test
    void shouldRenderConfiguredPolicyAndBoundaryRules() {
        TranslationPromptProperties properties = new TranslationPromptProperties();
        properties.getGlobal().setAccuracyPolicy("准确、忠实、自然、克制优先。");
        properties.getGlobal().setStyleWarnings(List.of("不要追逐华丽辞藻。"));
        properties.getDraftRound().setCoreInstructions(List.of("正文不得写入知识卡内容。"));

        TranslationPromptRenderer renderer = new TranslationPromptRenderer(properties);

        String prompt = renderer.renderDraftRound(createInput("zh"));

        assertTrue(prompt.contains("准确、忠实、自然、克制优先。"));
        assertTrue(prompt.contains("不要追逐华丽辞藻。"));
        assertTrue(prompt.contains("正文不得写入知识卡内容。"));
        assertFalse(prompt.contains("冷调诗性汉语"));
    }

    @Test
    void shouldRenderPersonAliasHintsAsReferenceOnly() {
        TranslationPromptRenderer renderer = new TranslationPromptRenderer(new TranslationPromptProperties());

        String prompt = renderer.renderDraftRound(createInput("zh"));

        assertTrue(prompt.contains("人名弱提示"));
        assertTrue(prompt.contains("仅供参考，不代表已确认事实"));
        assertTrue(prompt.contains("Bowling"));
        assertTrue(prompt.contains("le Capitaine"));
    }

    @Test
    void shouldRenderTargetLanguageSpecificDraftInstruction() {
        TranslationPromptRenderer renderer = new TranslationPromptRenderer(new TranslationPromptProperties());

        String prompt = renderer.renderDraftRound(createInput("en"));

        assertTrue(prompt.contains("目标语言"));
        assertFalse(prompt.contains("中文翻译草稿"));
        assertFalse(prompt.contains("中文初稿"));
    }

    @Test
    void shouldRenderRevisionPromptAsIssueDrivenRepair() {
        TranslationPromptRenderer renderer = new TranslationPromptRenderer(new TranslationPromptProperties());

        String prompt = renderer.renderRevisionRound(
                createInput("zh"),
                new ChunkTranslationLlmResult(
                        "Louki站在门口，露姬没有回头。",
                        "commentary",
                        List.of(new ChunkTranslationDecisionNoteResult(
                                "glossary-compliance-warning",
                                "Louki",
                                "检测到原文名与已确认译名在同一正文中混用。",
                                "请统一沿用当前已确认译名。"
                        )),
                        List.of(),
                        List.of(),
                        new ChunkTranslationTransitionNoteResult("", "", false)
                ),
                List.of(new TranslatedTextIssue(
                        "target-language-purity",
                        "检测到目标语言为中文时正文仍存在整句或整段外语残留。"
                ))
        );

        assertTrue(prompt.contains("按 issue 清单定向修订"));
        assertTrue(prompt.contains("active glossary"));
        assertTrue(prompt.contains("target-language-purity"));
    }

    private TranslationTaskInput createInput(String targetLanguage) {
        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, 20, "Bowling entered Le Conde."),
                "摘要",
                List.of("Bowling", "Le Conde"),
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
                new BookProject("project-1", "sample", "en", targetLanguage),
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
}
