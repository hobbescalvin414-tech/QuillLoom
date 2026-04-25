package io.quillloom.infrastructure.preprocess.chunksegmentation;

import io.quillloom.application.preprocess.model.ChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.ChunkSegmentationPlanningResult;
import io.quillloom.application.preprocess.model.ChunkSegmentationTaskInput;
import io.quillloom.infrastructure.preprocess.ParagraphView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对细切分规划结果做严格合法性校验，保证后续编译器拿到的是可执行段落边界。
 */
@Component
public class ChunkSegmentationPlanningLlmResultNormalizer {

    public ChunkSegmentationPlanningResult normalize(ChunkSegmentationTaskInput input,
                                                     ChunkSegmentationPlanningLlmResult rawResult) {
        if (rawResult == null || rawResult.boundaries() == null || rawResult.boundaries().isEmpty()) {
            throw new IllegalStateException("chunk segmentation LLM 未返回任何 boundaries。请检查 prompt 或模型输出。");
        }

        String blockId = input == null || input.coarseChunkBlock() == null ? "" : nullToEmpty(input.coarseChunkBlock().blockId());
        String sourceText = input == null || input.coarseChunkBlock() == null ? "" : nullToEmpty(input.coarseChunkBlock().sourceText());
        ParagraphView paragraphView = ParagraphView.from(sourceText);
        if (paragraphView.isEmpty()) {
            throw new IllegalStateException("chunk segmentation 输入文本未形成任何有效段落。blockId=" + blockId);
        }

        List<ChunkBoundaryPlan> normalized = new ArrayList<>();
        int previousParagraphIndex = 0;
        int lastParagraphIndex = paragraphView.paragraphs().size();
        List<ChunkSegmentationPlanningLlmBoundary> rawBoundaries = rawResult.boundaries();

        for (int i = 0; i < rawBoundaries.size(); i++) {
            ChunkSegmentationPlanningLlmBoundary boundary = rawBoundaries.get(i);
            if (boundary == null) {
                throw new IllegalStateException("chunk segmentation LLM 返回了 null boundary。blockId=" + blockId + ", boundaryIndex=" + i);
            }

            Integer endParagraphIndex = boundary.endParagraphIndex();
            String boundaryHint = normalizeHint(boundary.boundaryHint());

            if (endParagraphIndex == null) {
                throw new IllegalStateException("chunk segmentation LLM 返回的 endParagraphIndex 不能为空。blockId=" + blockId
                        + ", boundaryIndex=" + i + ", rawBoundaries=" + summarizeBoundaries(rawBoundaries));
            }
            if (endParagraphIndex < 1 || endParagraphIndex > lastParagraphIndex) {
                throw new IllegalStateException("chunk segmentation LLM 返回的 endParagraphIndex 越界。blockId=" + blockId
                        + ", boundaryIndex=" + i + ", endParagraphIndex=" + endParagraphIndex
                        + ", paragraphCount=" + lastParagraphIndex
                        + ", rawBoundaries=" + summarizeBoundaries(rawBoundaries));
            }
            if (endParagraphIndex <= previousParagraphIndex) {
                throw new IllegalStateException("chunk segmentation LLM 返回的 endParagraphIndex 没有递增。blockId=" + blockId
                        + ", boundaryIndex=" + i + ", previousParagraphIndex=" + previousParagraphIndex
                        + ", endParagraphIndex=" + endParagraphIndex
                        + ", rawBoundaries=" + summarizeBoundaries(rawBoundaries));
            }

            previousParagraphIndex = endParagraphIndex;
            normalized.add(new ChunkBoundaryPlan(endParagraphIndex, boundaryHint));
        }

        if (previousParagraphIndex != lastParagraphIndex) {
            throw new IllegalStateException("chunk segmentation LLM 未覆盖最后一个段落。blockId=" + blockId
                    + ", lastBoundaryParagraphIndex=" + previousParagraphIndex
                    + ", lastParagraphIndex=" + lastParagraphIndex
                    + ", rawBoundaries=" + summarizeBoundaries(rawBoundaries));
        }

        return new ChunkSegmentationPlanningResult(List.copyOf(normalized));
    }

    private String normalizeHint(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String summarizeBoundaries(List<ChunkSegmentationPlanningLlmBoundary> rawBoundaries) {
        return rawBoundaries.stream()
                .map(boundary -> {
                    if (boundary == null) {
                        return "{null}";
                    }
                    return "{endParagraphIndex=" + boundary.endParagraphIndex()
                            + ", boundaryHint=" + normalizeHint(boundary.boundaryHint()) + "}";
                })
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}