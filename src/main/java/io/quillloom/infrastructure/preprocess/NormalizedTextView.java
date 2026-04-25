package io.quillloom.infrastructure.preprocess;

import java.util.ArrayList;
import java.util.List;

/**
 * 为边界规划提供稳定的规范化文本视图，并保留回映原文 offset 的能力。
 * 规范化规则：
 * 1. 保留段落结构，段与段之间统一为两个换行。
 * 2. 段内换行和连续空白统一折叠为单个空格。
 */
public final class NormalizedTextView {

    private final String rawText;
    private final String normalizedText;
    private final int[] normalizedToRaw;

    private NormalizedTextView(String rawText,
                               String normalizedText,
                               int[] normalizedToRaw) {
        this.rawText = rawText;
        this.normalizedText = normalizedText;
        this.normalizedToRaw = normalizedToRaw;
    }

    public static NormalizedTextView from(String rawText) {
        String source = rawText == null ? "" : rawText;
        StringBuilder normalized = new StringBuilder();
        List<Integer> mapping = new ArrayList<>();

        boolean pendingWhitespace = false;
        int pendingWhitespaceRawIndex = -1;
        int pendingLineBreakCount = 0;

        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (Character.isWhitespace(ch)) {
                pendingWhitespace = true;
                if (pendingWhitespaceRawIndex < 0) {
                    pendingWhitespaceRawIndex = i;
                }
                if (ch == '\r') {
                    pendingLineBreakCount++;
                    if (i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                        i++;
                    }
                } else if (ch == '\n') {
                    pendingLineBreakCount++;
                }
                continue;
            }

            if (pendingWhitespace) {
                appendPendingSeparator(normalized, mapping, pendingWhitespaceRawIndex, pendingLineBreakCount);
                pendingWhitespace = false;
                pendingWhitespaceRawIndex = -1;
                pendingLineBreakCount = 0;
            }

            normalized.append(ch);
            mapping.add(i);
        }

        int[] normalizedToRaw = mapping.stream().mapToInt(Integer::intValue).toArray();
        return new NormalizedTextView(source, normalized.toString(), normalizedToRaw);
    }

    public static String normalizeSnippet(String value) {
        return from(value).normalizedText();
    }

    public String rawText() {
        return rawText;
    }

    public String normalizedText() {
        return normalizedText;
    }

    public int find(String normalizedAnchor, int fromIndex) {
        return normalizedText.indexOf(normalizedAnchor, Math.max(0, fromIndex));
    }

    public int rawExclusiveEndFor(int normalizedStart, int normalizedEndExclusive) {
        if (normalizedStart < 0 || normalizedEndExclusive <= normalizedStart || normalizedEndExclusive > normalizedToRaw.length) {
            throw new IllegalArgumentException("Invalid normalized range.");
        }
        return normalizedToRaw[normalizedEndExclusive - 1] + 1;
    }

    public String preview(int normalizedCursor, int maxChars) {
        if (normalizedText.isBlank()) {
            return "";
        }
        int start = Math.max(0, Math.min(normalizedCursor, normalizedText.length()));
        int end = Math.min(normalizedText.length(), start + Math.max(1, maxChars));
        return normalizedText.substring(start, end);
    }

    private static void appendPendingSeparator(StringBuilder normalized,
                                               List<Integer> mapping,
                                               int rawIndex,
                                               int lineBreakCount) {
        if (normalized.length() == 0 || rawIndex < 0) {
            return;
        }
        if (lineBreakCount >= 2) {
            normalized.append('\n');
            mapping.add(rawIndex);
            normalized.append('\n');
            mapping.add(rawIndex);
            return;
        }
        normalized.append(' ');
        mapping.add(rawIndex);
    }
}
