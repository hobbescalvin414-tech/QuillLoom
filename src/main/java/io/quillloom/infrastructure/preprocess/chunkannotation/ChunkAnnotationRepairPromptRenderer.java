package io.quillloom.infrastructure.preprocess.chunkannotation;

import org.springframework.stereotype.Component;

@Component
public class ChunkAnnotationRepairPromptRenderer {

    public String render(String originalPrompt, ChunkAnnotationRepairIssue issue) {
        StringBuilder builder = new StringBuilder();
        builder.append("你上一次的 chunk annotation 输出失败了。\n");
        builder.append("这不是让你重做任务，而是让你只修复结构化输出。\n");
        builder.append("上一次输出失败原因：").append(nullToEmpty(issue.detail())).append("\n");
        builder.append("失败类型：").append(nullToEmpty(issue.reasonCode())).append("\n");
        builder.append("请基于原任务重新输出完整、闭合、可解析的 JSON。\n");
        builder.append("不要输出解释，不要输出 Markdown，不要输出代码块。\n");
        builder.append("保持字段集合不变：summary、entities、backgroundQuestions、translationRisks、keyExpressions、personAliasHints。\n");
        builder.append("summary 只写 1 句。\n");
        builder.append("translationRisks 最多 3 项，每项只写 1 条短风险点。\n");
        builder.append("如果某字段没有内容，返回空数组，不要编造。\n");
        builder.append("所有字符串必须正确闭合。\n\n");
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
