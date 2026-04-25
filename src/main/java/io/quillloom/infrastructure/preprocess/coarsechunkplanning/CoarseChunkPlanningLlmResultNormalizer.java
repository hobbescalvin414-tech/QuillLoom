package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import io.quillloom.application.preprocess.model.CoarseChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningResult;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningTaskInput;
import io.quillloom.infrastructure.preprocess.ParagraphView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对粗分块规划结果做严格合法性校验，保证后续编译器拿到的是可执行段落边界。
 */
@Component
public class CoarseChunkPlanningLlmResultNormalizer {

    public CoarseChunkPlanningResult normalize(CoarseChunkPlanningTaskInput input,
                                               CoarseChunkPlanningLlmResult rawResult) {
        if (rawResult == null || rawResult.boundaries() == null || rawResult.boundaries().isEmpty()) {
            throw new IllegalStateException("coarse chunk planning LLM 未返回任何 boundaries。请检查 prompt 或模型输出。");
        }

        ParagraphView paragraphView = ParagraphView.from(input == null ? "" : input.sourceText());
        if (paragraphView.isEmpty()) {
            throw new IllegalStateException("coarse chunk planning 输入文本未形成任何有效段落。");
        }

        List<CoarseChunkPlanningLlmBoundary> rawBoundaries = rawResult.boundaries();
        List<CoarseChunkBoundaryPlan> normalized = new ArrayList<>();
        int previousParagraphIndex = 0;
        int lastParagraphIndex = paragraphView.paragraphs().size();

        for (int i = 0; i < rawBoundaries.size(); i++) {
            CoarseChunkPlanningLlmBoundary boundary = rawBoundaries.get(i);
            if (boundary == null) {
                throw new IllegalStateException("coarse chunk planning LLM 返回了 null boundary，位置=" + i);
            }

            Integer endParagraphIndex = boundary.endParagraphIndex();
            String summary = normalizeText(boundary.summary());
            String boundaryHint = normalizeText(boundary.boundaryHint());

            if (endParagraphIndex == null) {
                throw new IllegalStateException("coarse chunk planning LLM 返回的 endParagraphIndex 不能为空。boundaryIndex=" + i
                        + ", rawBoundaries=" + summarizeBoundaries(rawBoundaries));
            }
            if (endParagraphIndex < 1 || endParagraphIndex > lastParagraphIndex) {
                throw new IllegalStateException("coarse chunk planning LLM 返回的 endParagraphIndex 越界。boundaryIndex=" + i
                        + ", endParagraphIndex=" + endParagraphIndex
                        + ", paragraphCount=" + lastParagraphIndex
                        + ", rawBoundaries=" + summarizeBoundaries(rawBoundaries));
            }
            if (endParagraphIndex <= previousParagraphIndex) {
                throw new IllegalStateException("coarse chunk planning LLM 返回的 endParagraphIndex 没有递增。boundaryIndex=" + i
                        + ", previousParagraphIndex=" + previousParagraphIndex
                        + ", endParagraphIndex=" + endParagraphIndex
                        + ", rawBoundaries=" + summarizeBoundaries(rawBoundaries));
            }

            previousParagraphIndex = endParagraphIndex;
            normalized.add(new CoarseChunkBoundaryPlan(endParagraphIndex, summary, boundaryHint));
        }

        if (previousParagraphIndex != lastParagraphIndex) {
            throw new IllegalStateException("coarse chunk planning LLM 未覆盖最后一个段落。lastBoundaryParagraphIndex="
                    + previousParagraphIndex + ", lastParagraphIndex=" + lastParagraphIndex
                    + ", rawBoundaries=" + summarizeBoundaries(rawBoundaries));
        }

        return new CoarseChunkPlanningResult(List.copyOf(normalized));
    }

    private String summarizeBoundaries(List<CoarseChunkPlanningLlmBoundary> rawBoundaries) {
        return rawBoundaries.stream()
                .map(boundary -> {
                    if (boundary == null) {
                        return "{null}";
                    }
                    return "{endParagraphIndex=" + boundary.endParagraphIndex()
                            + ", summary=" + normalizeText(boundary.summary())
                            + ", boundaryHint=" + normalizeText(boundary.boundaryHint()) + "}";
                })
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}