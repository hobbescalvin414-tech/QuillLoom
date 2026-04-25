package io.quillloom.support;

import io.quillloom.application.preprocess.assembler.BookAnalysisTaskInputAssembler;
import io.quillloom.application.preprocess.assembler.CoarseChunkPlanningTaskInputAssembler;
import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import io.quillloom.application.preprocess.model.BookAnalysisTaskResult;
import io.quillloom.application.preprocess.model.CoarseChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningResult;
import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.infrastructure.preprocess.ParagraphView;
import io.quillloom.infrastructure.preprocess.PreprocessBookAnalyzer;
import io.quillloom.infrastructure.preprocess.coarsechunkplanning.CoarseChunkPlanCompiler;

import java.util.List;

/**
 * 为测试提供 Agent A 的最小可运行假实现，避免测试依赖真实 LLM。
 */
public final class BookAnalysisTestSupport {

    private BookAnalysisTestSupport() {
    }

    public static PreprocessBookAnalyzer createBookAnalyzer() {
        return new PreprocessBookAnalyzer(
                new BookAnalysisTaskInputAssembler(),
                BookAnalysisTestSupport::createBookAnalysisTaskResult,
                new CoarseChunkPlanningTaskInputAssembler(),
                input -> new CoarseChunkPlanningResult(List.of(
                        new CoarseChunkBoundaryPlan(paragraphCount(input.sourceText()), summarize(input.sourceText(), 120), "测试中退化为全文单块。")
                )),
                new CoarseChunkPlanCompiler()
        );
    }

    private static BookAnalysisTaskResult createBookAnalysisTaskResult(BookAnalysisTaskInput input) {
        String synopsis = summarize(input.sourceText(), 180);
        BookAnalysis bookAnalysis = new BookAnalysis(
                synopsis,
                "测试用全书叙事结构概述。",
                "全书风格画像",
                List.of("全书风险提示"),
                List.of("测试用翻译策略提示")
        );
        return new BookAnalysisTaskResult(
                bookAnalysis,
                List.of(
                        new GlobalConstraint("style-draft", "style", "约束关注"),
                        new GlobalConstraint("naming-consistency", "consistency", "保持全书命名一致")
                )
        );
    }

    private static int paragraphCount(String text) {
        return ParagraphView.from(text).paragraphs().size();
    }

    private static String summarize(String text, int limit) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit);
    }
}