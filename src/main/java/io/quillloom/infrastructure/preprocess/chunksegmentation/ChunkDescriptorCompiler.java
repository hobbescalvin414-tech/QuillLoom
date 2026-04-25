package io.quillloom.infrastructure.preprocess.chunksegmentation;

import io.quillloom.application.preprocess.model.ChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.ChunkSegmentationPlanningResult;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import io.quillloom.infrastructure.preprocess.ParagraphSegment;
import io.quillloom.infrastructure.preprocess.ParagraphView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据段落边界规划结果，在 coarse block 原文上执行真正的 chunk 切割。
 */
@Component
public class ChunkDescriptorCompiler {

    public List<ChunkDescriptor> compile(CoarseChunkBlock block, ChunkSegmentationPlanningResult planningResult) {
        if (block == null || block.sourceText() == null || block.sourceText().isBlank()) {
            return List.of();
        }

        String text = block.sourceText();
        List<ChunkBoundaryPlan> boundaries = planningResult == null ? List.of() : planningResult.boundaries();
        if (boundaries.isEmpty()) {
            return List.of(createChunk(block, 0, text.length(), 1));
        }

        ParagraphView paragraphView = ParagraphView.from(text);
        if (paragraphView.isEmpty()) {
            return List.of(createChunk(block, 0, text.length(), 1));
        }

        List<ChunkDescriptor> chunks = new ArrayList<>();
        int rawCursor = 0;
        int previousParagraphIndex = 0;
        int localSequence = 1;
        for (int i = 0; i < boundaries.size(); i++) {
            ChunkBoundaryPlan boundary = boundaries.get(i);
            int endParagraphIndex = boundary.endParagraphIndex();
            if (endParagraphIndex < 1 || endParagraphIndex > paragraphView.paragraphs().size()) {
                throw new IllegalStateException("chunk boundary endParagraphIndex out of range. boundaryIndex=" + i
                        + ", endParagraphIndex=" + endParagraphIndex
                        + ", paragraphCount=" + paragraphView.paragraphs().size());
            }
            if (endParagraphIndex <= previousParagraphIndex) {
                throw new IllegalStateException("chunk boundary endParagraphIndex must be strictly increasing. boundaryIndex=" + i
                        + ", previousParagraphIndex=" + previousParagraphIndex
                        + ", endParagraphIndex=" + endParagraphIndex);
            }

            ParagraphSegment endParagraph = paragraphView.paragraphAt(endParagraphIndex);
            int rawEnd = endParagraph.endOffset();
            if (rawEnd <= rawCursor) {
                throw new IllegalStateException("chunk boundary produced a non-positive raw slice. boundaryIndex=" + i
                        + ", rawCursor=" + rawCursor + ", rawEnd=" + rawEnd);
            }

            chunks.add(createChunk(block, rawCursor, rawEnd, localSequence));
            rawCursor = rawEnd;
            previousParagraphIndex = endParagraphIndex;
            localSequence++;
        }

        if (previousParagraphIndex != paragraphView.paragraphs().size()) {
            throw new IllegalStateException("chunk segmentation planning result did not cover the final paragraph. blockId="
                    + block.blockId() + ", previousParagraphIndex=" + previousParagraphIndex
                    + ", paragraphCount=" + paragraphView.paragraphs().size());
        }

        if (rawCursor < text.length()) {
            int trimmedTailStart = rawCursor;
            while (trimmedTailStart < text.length() && Character.isWhitespace(text.charAt(trimmedTailStart))) {
                trimmedTailStart++;
            }
            if (trimmedTailStart < text.length()) {
                throw new IllegalStateException("chunk segmentation planning result did not cover the full coarse block text. blockId="
                        + block.blockId() + ", rawCursor=" + rawCursor + ", textLength=" + text.length());
            }
        }

        return List.copyOf(chunks.stream()
                .filter(chunk -> chunk.startOffset() < chunk.endOffset())
                .toList());
    }

    private ChunkDescriptor createChunk(CoarseChunkBlock block, int start, int end, int localSequence) {
        String text = block.sourceText();
        int[] trimmedRange = trimRange(text, start, end);
        int absoluteStart = block.startOffset() + trimmedRange[0];
        int absoluteEnd = block.startOffset() + trimmedRange[1];
        return new ChunkDescriptor(
                block.blockId() + "-chunk-" + localSequence,
                localSequence,
                block.blockId(),
                absoluteStart,
                absoluteEnd,
                trimmedRange[0] >= trimmedRange[1] ? "" : text.substring(trimmedRange[0], trimmedRange[1])
        );
    }

    private int[] trimRange(String text, int start, int end) {
        int trimmedStart = start;
        int trimmedEnd = end;
        while (trimmedStart < trimmedEnd && Character.isWhitespace(text.charAt(trimmedStart))) {
            trimmedStart++;
        }
        while (trimmedEnd > trimmedStart && Character.isWhitespace(text.charAt(trimmedEnd - 1))) {
            trimmedEnd--;
        }
        return new int[]{trimmedStart, trimmedEnd};
    }
}