package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeNeedPlanningPromptRendererTest {

    @Test
    void shouldRenderTargetLanguageSpecificQueryGuidanceForChinese() {
        KnowledgeNeedPlanningPromptRenderer renderer = new KnowledgeNeedPlanningPromptRenderer();

        String prompt = renderer.render(createChunk(), "zh");

        assertTrue(prompt.contains("目标语言"));
        assertTrue(prompt.contains("中文翻译"));
        assertTrue(prompt.contains("不要默认把查询写成 English translation"));
    }

    @Test
    void shouldRenderTargetLanguageSpecificQueryGuidanceForEnglish() {
        KnowledgeNeedPlanningPromptRenderer renderer = new KnowledgeNeedPlanningPromptRenderer();

        String prompt = renderer.render(createChunk(), "en");

        assertTrue(prompt.contains("英文翻译"));
    }

    @Test
    void shouldPrioritizeRecurringProperNamesAsTranslationConsistencyNeeds() {
        KnowledgeNeedPlanningPromptRenderer renderer = new KnowledgeNeedPlanningPromptRenderer();

        String prompt = renderer.render(createChunk(), "zh");

        assertTrue(prompt.contains("高频、跨 chunk、首次出现且可能反复出现的人名、地名、场所名、店名、机构名"));
        assertTrue(prompt.contains("优先知识需求"));
        assertTrue(prompt.contains("即使外部搜索不一定命中"));
        assertTrue(prompt.contains("译名统一锚点"));
    }

    private ChunkAnnotation createChunk() {
        return new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, 20, "Dans le cafe"),
                "summary",
                List.of("Dans le cafe"),
                List.of("What does the title imply?"),
                List.of("Title may need translation support."),
                List.of("Dans le cafe")
        );
    }
}
