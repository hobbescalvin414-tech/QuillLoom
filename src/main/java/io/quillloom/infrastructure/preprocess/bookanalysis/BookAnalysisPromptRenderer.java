package io.quillloom.infrastructure.preprocess.bookanalysis;

import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import org.springframework.stereotype.Component;

/**
 * 将 Agent A 的结构化任务输入渲染为中文提示词。
 * 这里只负责提示词构造，不负责模型调用与结果解析。
 */
@Component
public class BookAnalysisPromptRenderer {

    public String render(BookAnalysisTaskInput input) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是小说预处理阶段的 Agent A，全书分析助手。\n");
        builder.append("你的任务是基于整本书原文，输出结构化的全书分析结果，而不是翻译正文。\n");
        builder.append("你必须从全书视角总结叙事结构、风格、全局风险和翻译策略，并提炼可执行的全局约束。\n\n");
        builder.append("【项目信息】\n");
        builder.append("项目ID：").append(nullToEmpty(input.projectId())).append("\n");
        builder.append("书名：").append(nullToEmpty(input.title())).append("\n");
        builder.append("源语言：").append(nullToEmpty(input.sourceLanguage())).append("\n");
        builder.append("目标语言：").append(nullToEmpty(input.targetLanguage())).append("\n\n");
        builder.append("【任务要求】\n");
        builder.append("1. synopsis：概括整本书主要内容，控制在 500 字以内。\n");
        builder.append("2. narrativeOutline：概括全书叙事结构、主要推进方式和视角特点。\n");
        builder.append("3. styleProfile：概括语言风格、叙述气质和翻译时应保持的语体特征。\n");
        builder.append("4. globalRisks：列出全书级翻译风险，例如世界观术语、人名译法、一致性风险、文化背景风险。\n");
        builder.append("5. translationStrategyNotes：列出全书级翻译策略提示。\n");
        builder.append("6. globalConstraints：列出后续阶段必须遵守的全局约束，每项包含 type 和 description。\n");
        builder.append("7. 禁止输出 Markdown、解释文字或代码块，只能输出一个 JSON 对象。\n\n");
        builder.append("【globalConstraints 边界】\n");
        builder.append("globalConstraints 只允许输出全书级、长期稳定、可跨 chunk 复用的约束。\n");
        builder.append("不要把单个人名、单个称呼、单个地名的具体译法、不译决定、括号注规则写成全局约束。\n");
        builder.append("像“Louki 保留不译”这类针对单一实体的硬编码规则，不要写入 globalConstraints。\n");
        builder.append("如果只是某个人名、称呼、地名或专名的候选译法、可疑判断、局部命名策略，应放入 globalRisks 或 translationStrategyNotes，而不是 globalConstraints。\n");
        builder.append("globalConstraints 更适合输出这类内容：术语一致性原则、风格边界、格式规范、长期适用的命名治理原则。\n\n");
        builder.append("【专名候选译名要求】\n");
        builder.append("尽可能识别全书高频或关键的人名、地名、场所名、店名、机构名，并在 globalRisks 或 translationStrategyNotes 中给出候选中文译名。\n");
        builder.append("即使外部证据不足，也可以基于音译、语源特征、目标语出版习惯提出候选中文译名，但必须明确这是候选而非已确认译名。\n");
        builder.append("不得把候选译名写成 globalConstraints；globalConstraints 只放长期稳定规则，不放单一实体的硬编码译法。\n\n");
        builder.append("【全书原文】\n");
        builder.append(nullToEmpty(input.sourceText())).append("\n\n");
        builder.append("JSON 必须严格包含以下字段：synopsis、narrativeOutline、styleProfile、globalRisks、translationStrategyNotes、globalConstraints。\n");
        builder.append("其中 globalConstraints 的每个元素都必须包含：type、description。\n");
        return builder.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
