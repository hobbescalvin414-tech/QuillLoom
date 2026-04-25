package io.quillloom.domain.preprocess;

import java.util.List;

/**
 * Book-level structured output from the global analysis stage.
 * - `synopsis`：全书概要，回答“这本书讲了什么”
 * - `narrativeOutline`：叙事结构概述，回答“这本书是怎么讲的”
 * - `styleProfile`：风格画像，回答“语言和叙述是什么味道”
 * - `globalRisks`：全书级风险点
 * - `translationStrategyNotes`：全局翻译策略提示
 */
public record BookAnalysis(
        String synopsis,
        String narrativeOutline,
        String styleProfile,
        List<String> globalRisks,
        List<String> translationStrategyNotes
) {
}
