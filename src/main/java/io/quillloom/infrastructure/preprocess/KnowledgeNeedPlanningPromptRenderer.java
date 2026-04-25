package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeNeedPlanningPromptRenderer {

    public String render(ChunkAnnotation chunk) {
        return render(chunk, "");
    }

    public String render(ChunkAnnotation chunk, String targetLanguage) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 C0 的知识需求规划器，只输出 JSON。\n");
        builder.append("目标：充分消费当前 chunk 标注中的多类信号，为当前 chunk 规划一批高价值知识需求，服务翻译，不做无关百科扩写。\n");
        builder.append("目标语言：").append(nullToEmpty(targetLanguage)).append("\n");
        appendTargetLanguageGuidance(builder, targetLanguage);
        builder.append("约束：\n");
        builder.append("1. 只选择对当前 chunk 翻译直接有帮助的知识。\n");
        builder.append("2. 必须尽量覆盖 backgroundQuestions、translationRisks、keyExpressions、entities 中有价值的信号，不要只围绕人名地名。\n");
        builder.append("3. anchorNames 只能放稳定锚点，不能放完整问题句。\n");
        builder.append("4. cardType 只能是 HISTORICAL_BACKGROUND、CULTURAL_BACKGROUND、IMAGERY、SETTING_ENTRY、TERM_EXPLANATION、CHARACTER_PROFILE。\n");
        builder.append("5. needKind 只能是 BACKGROUND_CONTEXT、TRANSLATION_SUPPORT、EXPRESSION_CONTEXT、ENTITY_PROFILE、GENERAL_ENRICHMENT。\n");
        builder.append("6. signalSource 只能是 backgroundQuestion、translationRisk、keyExpression、entity。\n");
        builder.append("7. queryText 必须是可直接放进搜索框的短搜索词，优先 3 到 8 个词，不要写分析句、问句或解释句。\n");
        builder.append("8. 对 translationRisks，可搜索称谓、语体、修辞、典故、译法策略、宗教礼仪、历史语境等支持性信息。\n");
        builder.append("9. 对 keyExpressions，可搜索意象来源、固定表达、文化含义、文学语境。\n");
        builder.append("10. coverageKey 用稳定英文短键表达本 need 覆盖的核心主题，便于后续去重。\n");
        builder.append("11. searchIntent 用短标签描述搜索意图，例如 cultural_norm、historical_context、translation_strategy、imagery_origin。\n");
        builder.append("12. 如果某类信号明显低价值或重复，可不展开；否则优先覆盖。\n\n");
        builder.append("[chunk]\n");
        builder.append("chunkId: ").append(chunk.chunk().chunkId()).append("\n");
        builder.append("summary: ").append(nullToEmpty(chunk.summary())).append("\n");
        builder.append("entities: ").append(renderList(chunk.entities())).append("\n");
        builder.append("backgroundQuestions: ").append(renderList(chunk.backgroundQuestions())).append("\n");
        builder.append("translationRisks: ").append(renderList(chunk.translationRisks())).append("\n");
        builder.append("keyExpressions: ").append(renderList(chunk.keyExpressions())).append("\n\n");
        builder.append("只输出一个 JSON 对象，格式为：\n");
        builder.append("{\"needs\":[{\"shouldSearch\":true,\"needKind\":\"BACKGROUND_CONTEXT\",\"signalSource\":\"backgroundQuestion\",\"searchIntent\":\"cultural_norm\",\"coverageKey\":\"victorian-parish-priest-etiquette\",\"cardType\":\"CULTURAL_BACKGROUND\",\"queryText\":\"...\",\"anchorNames\":[\"...\"],\"keywords\":[\"...\"],\"originRefs\":[\"chunk:...\"],\"reason\":\"...\",\"priority\":1}]}\n");
        builder.append("13. 高频、跨 chunk、首次出现且可能反复出现的人名、地名、场所名、店名、机构名，是优先知识需求；即使外部搜索不一定命中，也应保留为后续译名统一锚点。\n");
        return builder.toString();
    }

    private void appendTargetLanguageGuidance(StringBuilder builder, String targetLanguage) {
        if ("zh".equalsIgnoreCase(targetLanguage)) {
            builder.append("查询语言要求：优先服务中文翻译任务，优先搜索中文通行译名、中文出版惯例、中文译法讨论与中文背景资料；不要默认把查询写成 English translation 一类英文译名查询。\n");
            return;
        }
        if ("en".equalsIgnoreCase(targetLanguage)) {
            builder.append("查询语言要求：优先服务英文翻译任务，优先搜索英文通行译名、英文出版惯例、英文译法讨论与英文背景资料。\n");
            return;
        }
        if (targetLanguage != null && !targetLanguage.isBlank()) {
            builder.append("查询语言要求：优先服务目标语言翻译任务，查询措辞应尽量贴合目标语言常见译名、出版惯例与背景资料。\n");
        }
    }

    private String renderList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
