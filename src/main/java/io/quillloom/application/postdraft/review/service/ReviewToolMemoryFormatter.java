package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ToolCallSignature;

import java.util.List;
import java.util.Objects;

public final class ReviewToolMemoryFormatter {

    private ReviewToolMemoryFormatter() {
    }

    public static String renderReadConfirmedTermsUse(ToolCallSignature signature) {
        ToolCallSignature safeSignature = Objects.requireNonNull(signature, "signature");
        return "tool_use read_confirmed_terms {\"sourceTerms\":[" + renderJsonStringArray(sourceTermsDisplay(safeSignature)) + "]}";
    }

    public static String renderToolResult(ToolCallSignature signature, List<String> summaries) {
        ToolCallSignature safeSignature = Objects.requireNonNull(signature, "signature");
        String summary = summaries == null || summaries.isEmpty()
                ? "no_result"
                : String.join("; ", summaries);
        return "tool_result read_confirmed_terms " + sourceTermsSegment(safeSignature) + " -> " + summary;
    }

    public static String renderRedundantToolCallHint(ToolCallSignature signature) {
        ToolCallSignature safeSignature = Objects.requireNonNull(signature, "signature");
        return "local_replan_hint -> 已经成功查过 " + safeSignature.display()
                + "；不要重复查询。当前证据足够则调用 complete_working_set，发现问题则调用 evaluate_focus，"
                + "本地工具仍无法判断时再 request_human_review。";
    }

    private static String renderJsonStringArray(String sourceTermsDisplay) {
        if (sourceTermsDisplay.isBlank()) {
            return "";
        }
        String[] terms = sourceTermsDisplay.split(", ");
        StringBuilder builder = new StringBuilder();
        for (String term : terms) {
            if (!builder.isEmpty()) {
                builder.append(",");
            }
            builder.append("\"").append(escapeJson(term)).append("\"");
        }
        return builder.toString();
    }

    private static String sourceTermsSegment(ToolCallSignature signature) {
        return "sourceTerms=[" + sourceTermsDisplay(signature) + "]";
    }

    private static String sourceTermsDisplay(ToolCallSignature signature) {
        String display = signature.display();
        String prefix = "read_confirmed_terms sourceTerms=[";
        if (!display.startsWith(prefix) || !display.endsWith("]")) {
            return display;
        }
        return display.substring(prefix.length(), display.length() - 1);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
