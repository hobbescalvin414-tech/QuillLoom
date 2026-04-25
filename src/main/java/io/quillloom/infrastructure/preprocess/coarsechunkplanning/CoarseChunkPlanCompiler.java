package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import io.quillloom.application.preprocess.model.CoarseChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningResult;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import io.quillloom.domain.preprocess.CoarseChunkPlan;
import io.quillloom.infrastructure.preprocess.NormalizedTextView;
import io.quillloom.infrastructure.preprocess.ParagraphSegment;
import io.quillloom.infrastructure.preprocess.ParagraphView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据段落边界规划结果，在原文上执行真正的粗分块切割。
 */
@Component
public class CoarseChunkPlanCompiler {

    public CoarseChunkPlan compile(String sourceText, CoarseChunkPlanningResult planningResult) {
        String text = sourceText == null ? "" : sourceText;
        if (text.isBlank()) {
            return CoarseChunkPlan.empty();
        }

        List<CoarseChunkBoundaryPlan> boundaries = planningResult == null ? List.of() : planningResult.boundaries();
        if (boundaries.isEmpty()) {
            throw new IllegalStateException("coarse chunk planning result does not contain boundaries.");
        }

        ParagraphView paragraphView = ParagraphView.from(text);
        if (paragraphView.isEmpty()) {
            throw new IllegalStateException("coarse chunk plan compiler could not derive any paragraphs from source text.");
        }

        List<CoarseChunkBlock> blocks = new ArrayList<>();
        int rawCursor = 0;
        int previousParagraphIndex = 0;
        int sequence = 1;

        for (int i = 0; i < boundaries.size(); i++) {
            CoarseChunkBoundaryPlan boundary = boundaries.get(i);
            int endParagraphIndex = boundary.endParagraphIndex();
            if (endParagraphIndex < 1 || endParagraphIndex > paragraphView.paragraphs().size()) {
                throw new IllegalStateException("coarse chunk boundary endParagraphIndex out of range. boundaryIndex=" + i
                        + ", endParagraphIndex=" + endParagraphIndex
                        + ", paragraphCount=" + paragraphView.paragraphs().size());
            }
            if (endParagraphIndex <= previousParagraphIndex) {
                throw new IllegalStateException("coarse chunk boundary endParagraphIndex must be strictly increasing. boundaryIndex=" + i
                        + ", previousParagraphIndex=" + previousParagraphIndex
                        + ", endParagraphIndex=" + endParagraphIndex);
            }

            ParagraphSegment endParagraph = paragraphView.paragraphAt(endParagraphIndex);
            int rawEnd = endParagraph.endOffset();
            if (rawEnd <= rawCursor) {
                throw new IllegalStateException("coarse chunk boundary produced a non-positive raw slice. boundaryIndex=" + i
                        + ", rawCursor=" + rawCursor + ", rawEnd=" + rawEnd);
            }

            blocks.add(createBlock(text, rawCursor, rawEnd, sequence, boundary.summary(), boundary.boundaryHint()));
            rawCursor = rawEnd;
            previousParagraphIndex = endParagraphIndex;
            sequence++;
        }

        if (previousParagraphIndex != paragraphView.paragraphs().size()) {
            throw new IllegalStateException("coarse chunk planning result did not cover the final paragraph. previousParagraphIndex="
                    + previousParagraphIndex + ", paragraphCount=" + paragraphView.paragraphs().size());
        }

        if (rawCursor < text.length()) {
            int trimmedTailStart = rawCursor;
            while (trimmedTailStart < text.length() && Character.isWhitespace(text.charAt(trimmedTailStart))) {
                trimmedTailStart++;
            }
            if (trimmedTailStart < text.length()) {
                throw new IllegalStateException("coarse chunk planning result did not cover the full source text. rawCursor="
                        + rawCursor + ", textLength=" + text.length());
            }
        }

        return new CoarseChunkPlan(blocks.stream()
                .filter(block -> block.startOffset() < block.endOffset())
                .toList());
    }

    private CoarseChunkBlock createBlock(String text,
                                         int start,
                                         int end,
                                         int sequence,
                                         String summary,
                                         String boundaryHint) {
        int[] trimmedRange = trimRange(text, start, end);
        String sourceSlice = trimmedRange[0] >= trimmedRange[1] ? "" : text.substring(trimmedRange[0], trimmedRange[1]);
        return new CoarseChunkBlock(
                "block-" + sequence,
                sequence,
                trimmedRange[0],
                trimmedRange[1],
                sourceSlice,
                isBlank(summary) ? summarize(sourceSlice) : summary,
                isBlank(boundaryHint) ? "根据模型段落边界执行代码切割。" : boundaryHint
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

    private String summarize(String sourceSlice) {
        String normalized = sourceSlice == null ? "" : NormalizedTextView.normalizeSnippet(sourceSlice);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.substring(0, Math.min(normalized.length(), 120));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}