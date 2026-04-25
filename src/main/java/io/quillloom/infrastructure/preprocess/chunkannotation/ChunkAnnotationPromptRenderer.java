package io.quillloom.infrastructure.preprocess.chunkannotation;

import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import org.springframework.stereotype.Component;

/**
 * 将结构化任务输入渲染为中文标注提示词。
 * 这里只负责渲染，不负责模型调用与结果解析。
 */
@Component
public class ChunkAnnotationPromptRenderer {

    public String render(ChunkAnnotationTaskInput input) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是小说预处理阶段的 chunk 标注助手。\n");
        builder.append("你的任务不是翻译，而是为后续翻译阶段生成结构化 chunk 标注。\n");
        builder.append("请优先输出稳定、简洁、可解析的 JSON，不要写成长篇分析。\n\n");

        builder.append("【项目】\n");
        builder.append("项目ID：").append(input.projectId()).append("\n");
        builder.append("书名：").append(input.title()).append("\n");
        builder.append("源语言：").append(input.sourceLanguage()).append("\n");
        builder.append("目标语言：").append(input.targetLanguage()).append("\n\n");

        builder.append("【全书分析】\n");
        if (input.bookAnalysis() != null) {
            builder.append("全书概要：").append(nullToEmpty(input.bookAnalysis().synopsis())).append("\n");
            builder.append("叙事结构：").append(nullToEmpty(input.bookAnalysis().narrativeOutline())).append("\n");
            builder.append("风格画像：").append(nullToEmpty(input.bookAnalysis().styleProfile())).append("\n");
        }

        builder.append("\n【全局约束】\n");
        if (input.globalConstraints() == null || input.globalConstraints().isEmpty()) {
            builder.append("- 无\n");
        } else {
            input.globalConstraints().stream()
                    .limit(5)
                    .forEach(constraint -> builder.append("- ").append(nullToEmpty(constraint.description())).append("\n"));
        }

        builder.append("\n【当前 chunk】\n");
        builder.append("chunkId：").append(input.chunk().chunkId()).append("\n");
        builder.append("sequence：").append(input.chunk().sequence()).append("\n");
        builder.append("原文：\n").append(input.chunk().sourceText()).append("\n\n");

        builder.append("请只输出一个 JSON 对象，不要输出 Markdown，不要输出解释，不要输出代码块。\n");
        builder.append("JSON 必须严格包含以下字段：summary、entities、backgroundQuestions、translationRisks、keyExpressions、personAliasHints。\n");
        builder.append("字段要求如下：\n");
        builder.append("1. summary：字符串，概括当前 chunk 的核心内容。只写 1 句，尽量控制在 40 到 120 个中文字符内。\n");
        builder.append("2. entities：字符串数组，列出当前 chunk 中重要人物、地点、组织、专有名词。优先保留最重要的 0 到 8 项。\n");
        builder.append("3. backgroundQuestions：字符串数组，列出翻译前值得确认的背景问题；没有可返回空数组。最多 3 项，每项尽量控制在 30 个中文字符内。\n");
        builder.append("4. translationRisks：字符串数组，列出潜在翻译风险；没有可返回空数组。最多 3 项，每项只写 1 条风险点，尽量控制在 30 个中文字符内，禁止把多条理由塞进同一项，禁止长句解释。\n");
        builder.append("5. keyExpressions：字符串数组，列出值得关注的关键表达；没有可返回空数组。优先保留最重要的 0 到 5 项。\n");
        builder.append("6. personAliasHints：数组，仅记录当前 chunk 内可能指向同一人物的不同称呼；仅供后续翻译参考，不代表已确认事实；没有可返回空数组。最多 3 项。\n");
        builder.append("7. 若某字段没有明显内容，返回空数组，不要为了凑满字段编造内容。\n");
        builder.append("8. 所有字段都要简短，避免长段说明，避免分号串联多个子结论，避免把一整段翻译分析塞进 summary 或 translationRisks。\n");

        builder.append("\n输出示例：\n");
        builder.append("{")
                .append("\"summary\":\"本段描写主角夜间抵达港口并观察周围局势\",")
                .append("\"entities\":[\"林远\",\"黑潮港\"],")
                .append("\"backgroundQuestions\":[\"黑潮港在本书世界中的势力归属是什么\"],")
                .append("\"translationRisks\":[\"港口黑话可能需要统一译法\"],")
                .append("\"keyExpressions\":[\"black tide harbor\",\"kept his hood low\"],")
                .append("\"personAliasHints\":[{\"surfaceForms\":[\"Mike\",\"Mikie\"],\"hintType\":\"same-person-name-variant\",\"confidence\":\"MEDIUM\",\"evidence\":\"同段称呼切换\"}]")
                .append("}\n");
        builder.append("补充实体覆盖要求：entities 要尽量覆盖人名、地名、场所名、店名、机构名、称谓和反复出现的专名；不要因为暂时无法确认译名就漏掉实体。\n");
        builder.append("补充风险标注要求：translationRisks 应标记可能造成译名不一致的实体，例如缺少稳定译名、需要统一译名、首次出现但可能跨 chunk 反复出现。\n");
        return builder.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
