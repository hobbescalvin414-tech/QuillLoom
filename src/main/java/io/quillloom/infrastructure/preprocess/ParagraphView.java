package io.quillloom.infrastructure.preprocess;

import java.util.ArrayList;
import java.util.List;

/**
 * 将原文按段落切成稳定视图，供后续 A / B 使用。
 */
public final class ParagraphView {

    private final String rawText;
    private final List<ParagraphSegment> paragraphs;

    private ParagraphView(String rawText, List<ParagraphSegment> paragraphs) {
        this.rawText = rawText;
        this.paragraphs = List.copyOf(paragraphs);
    }

    public static ParagraphView from(String rawText) {
        String source = rawText == null ? "" : rawText;
        List<ParagraphSegment> segments = new ArrayList<>();

        int length = source.length();
        int cursor = 0;
        int paragraphIndex = 1;

        while (cursor < length) {
            while (cursor < length && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= length) {
                break;
            }

            int paragraphStart = cursor;
            int lineBreakRun = 0;

            while (cursor < length) {
                char ch = source.charAt(cursor);
                if (ch == '\r') {
                    lineBreakRun++;
                    cursor++;
                    if (cursor < length && source.charAt(cursor) == '\n') {
                        cursor++;
                    }
                    if (lineBreakRun >= 2) {
                        break;
                    }
                    continue;
                }
                if (ch == '\n') {
                    lineBreakRun++;
                    cursor++;
                    if (lineBreakRun >= 2) {
                        break;
                    }
                    continue;
                }
                if (Character.isWhitespace(ch)) {
                    cursor++;
                    continue;
                }
                lineBreakRun = 0;
                cursor++;
            }

            int paragraphEnd = cursor;
            while (paragraphEnd > paragraphStart && Character.isWhitespace(source.charAt(paragraphEnd - 1))) {
                paragraphEnd--;
            }
            if (paragraphEnd <= paragraphStart) {
                continue;
            }

            String rawParagraph = source.substring(paragraphStart, paragraphEnd);
            segments.add(new ParagraphSegment(
                    paragraphIndex,
                    paragraphStart,
                    paragraphEnd,
                    rawParagraph,
                    NormalizedTextView.normalizeSnippet(rawParagraph)
            ));
            paragraphIndex++;
        }

        return new ParagraphView(source, segments);
    }

    public String rawText() {
        return rawText;
    }

    public List<ParagraphSegment> paragraphs() {
        return paragraphs;
    }

    public boolean isEmpty() {
        return paragraphs.isEmpty();
    }

    public ParagraphSegment paragraphAt(int paragraphIndex) {
        if (paragraphIndex < 1 || paragraphIndex > paragraphs.size()) {
            throw new IllegalArgumentException("Invalid paragraphIndex=" + paragraphIndex);
        }
        return paragraphs.get(paragraphIndex - 1);
    }

    public String renderIndexedView() {
        StringBuilder builder = new StringBuilder();
        for (ParagraphSegment paragraph : paragraphs) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("P").append(paragraph.paragraphIndex()).append(": ")
                    .append(paragraph.normalizedText());
        }
        return builder.toString();
    }
}
