package io.quillloom.infrastructure.preprocess;

/**
 * 原文段落片段视图。
 */
public record ParagraphSegment(
        int paragraphIndex,
        int startOffset,
        int endOffset,
        String rawText,
        String normalizedText
) {
}
