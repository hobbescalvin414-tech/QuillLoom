package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import org.springframework.stereotype.Component;

@Component
public class CoarseChunkPlanningRepairPromptRenderer {

    public String render(String originalPrompt, CoarseChunkPlanningRepairIssue issue) {
        StringBuilder builder = new StringBuilder();
        builder.append("你上一次 coarse chunk planning 输出失败了。\n");
        builder.append("这不是让你重做任务，而是只修复边界结构问题。\n");
        builder.append("上一次失败原因：").append(nullToEmpty(issue.detail())).append("\n");
        builder.append("请重新输出完整、可解析、按顺序递增的 boundaries。\n");
        builder.append("只返回 JSON 对象，字段仍然只允许有 boundaries。\n");
        builder.append("每个边界仍然只包含 endParagraphIndex、summary、boundaryHint。\n");
        builder.append("endParagraphIndex 必须严格递增，最后一个边界必须覆盖最后一段。\n");
        builder.append("不要重写任务目标，不要输出解释，不要输出 Markdown。\n\n");
        builder.append("【上一次原始输出】\n");
        builder.append(nullToEmpty(issue.rawResponse())).append("\n\n");
        builder.append("【原始任务】\n");
        builder.append(nullToEmpty(originalPrompt));
        return builder.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
