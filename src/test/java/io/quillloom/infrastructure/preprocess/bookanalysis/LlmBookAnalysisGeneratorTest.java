package io.quillloom.infrastructure.preprocess.bookanalysis;

import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmBookAnalysisGeneratorTest {

    @Test
    void shouldDelegateToLlmClientAndReuseNormalizationChain() {
        BookAnalysisTaskInput input = new BookAnalysisTaskInput(
                "project-1",
                "示例小说",
                "Alice met Bob in Paris near the river. Bob warned her that the docks were being watched.",
                "en",
                "zh"
        );
        LlmBookAnalysisClient client = prompt -> {
            assertTrue(prompt.contains("你是小说预处理阶段的 Agent A，全书分析助手。"));
            return new BookAnalysisLlmResult(
                    "   ",
                    " 双线叙事，围绕港口阴谋推进。 ",
                    " 冷静克制 ",
                    List.of(" 术语一致性 ", "术语一致性"),
                    null,
                    List.of(new BookAnalysisLlmConstraint(" style ", " 保持人名与语体一致 "))
            );
        };
        LlmBookAnalysisGenerator generator = new LlmBookAnalysisGenerator(
                new BookAnalysisPromptRenderer(),
                client,
                new BookAnalysisLlmResultNormalizer(),
                new BookAnalysisLlmResultParser()
        );

        var result = generator.generate(input);

        assertFalse(result.bookAnalysis().synopsis().isBlank());
        assertEquals("双线叙事，围绕港口阴谋推进。", result.bookAnalysis().narrativeOutline());
        assertEquals("冷静克制", result.bookAnalysis().styleProfile());
        assertEquals(List.of("术语一致性"), result.bookAnalysis().globalRisks());
        assertEquals(List.of(), result.bookAnalysis().translationStrategyNotes());
        assertEquals(1, result.globalConstraints().size());
        assertEquals("style", result.globalConstraints().get(0).type());
        assertEquals("保持人名与语体一致", result.globalConstraints().get(0).description());
        assertEquals(List.of(), result.tracePayload().get("rejectedGlobalConstraints"));
    }

    @Test
    void shouldExposeRejectedGlobalConstraintsInTracePayload() {
        BookAnalysisTaskInput input = new BookAnalysisTaskInput(
                "project-1",
                "示例小说",
                "Louki met Jacqueline in Paris.",
                "fr",
                "zh"
        );
        LlmBookAnalysisClient client = prompt -> new BookAnalysisLlmResult(
                "简要概括",
                "叙事大纲",
                "冷静克制",
                List.of(),
                List.of(),
                List.of(
                        new BookAnalysisLlmConstraint("consistency", "所有专有名词保留法语原文不译，仅首次出现时加中文注释"),
                        new BookAnalysisLlmConstraint("consistency", "全书命名应保持一致，未确认译名不要在不同 chunk 之间随意漂移")
                )
        );
        LlmBookAnalysisGenerator generator = new LlmBookAnalysisGenerator(
                new BookAnalysisPromptRenderer(),
                client,
                new BookAnalysisLlmResultNormalizer(),
                new BookAnalysisLlmResultParser()
        );

        var result = generator.generate(input);

        assertEquals(1, result.globalConstraints().size());
        assertEquals("全书命名应保持一致，未确认译名不要在不同 chunk 之间随意漂移", result.globalConstraints().get(0).description());
        assertTrue(result.tracePayload().containsKey("acceptedGlobalConstraints"));
        assertTrue(result.tracePayload().containsKey("rejectedGlobalConstraints"));
        assertEquals(1, ((List<?>) result.tracePayload().get("rejectedGlobalConstraints")).size());
        Map<?, ?> rejected = (Map<?, ?>) ((List<?>) result.tracePayload().get("rejectedGlobalConstraints")).get(0);
        assertEquals("entity-level-do-not-translate", rejected.get("reasonCode"));
    }
}
