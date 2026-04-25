package io.quillloom.infrastructure.preprocess.bookanalysis;

import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BookAnalysisPromptRendererTest {

    @Test
    void shouldRenderChinesePromptForAgentA() {
        BookAnalysisTaskInput input = new BookAnalysisTaskInput(
                "project-1",
                "示例小说",
                "Alice met Bob in Paris.",
                "en",
                "zh"
        );

        String prompt = new BookAnalysisPromptRenderer().render(input);

        assertTrue(prompt.contains("你是小说预处理阶段的 Agent A，全书分析助手"));
        assertTrue(prompt.contains("书名：示例小说"));
        assertTrue(prompt.contains("【全书原文】"));
        assertTrue(prompt.contains("globalConstraints"));
    }

    @Test
    void shouldConstrainGlobalConstraintsToStableProjectRulesOnly() {
        BookAnalysisTaskInput input = new BookAnalysisTaskInput(
                "project-1",
                "示例小说",
                "Louki met Bowing in Paris.",
                "fr",
                "zh"
        );

        String prompt = new BookAnalysisPromptRenderer().render(input);

        assertTrue(prompt.contains("globalConstraints 只允许输出全书级、长期稳定、可跨 chunk 复用的约束"));
        assertTrue(prompt.contains("不要把单个人名、单个称呼、单个地名的具体译法、不译决定、括号注规则写成全局约束"));
        assertTrue(prompt.contains("像“Louki 保留不译”这类针对单一实体的硬编码规则，不要写入 globalConstraints"));
    }

    @Test
    void shouldAskForCandidateNameAndPlaceTranslationsWithoutPromotingThemToConstraints() {
        BookAnalysisTaskInput input = new BookAnalysisTaskInput(
                "project-1",
                "示例小说",
                "Louki met Roland near Le Condé.",
                "fr",
                "zh"
        );

        String prompt = new BookAnalysisPromptRenderer().render(input);

        assertTrue(prompt.contains("尽可能识别全书高频或关键的人名、地名、场所名、店名、机构名"));
        assertTrue(prompt.contains("即使外部证据不足"));
        assertTrue(prompt.contains("候选中文译名"));
        assertTrue(prompt.contains("不得把候选译名写成 globalConstraints"));
    }
}
