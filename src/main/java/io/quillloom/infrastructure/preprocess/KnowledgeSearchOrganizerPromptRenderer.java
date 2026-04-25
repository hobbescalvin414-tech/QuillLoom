package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeSearchOrganizerPromptRenderer {

    public String render(ChunkAnnotation chunk,
                         KnowledgeNeed need,
                         List<KnowledgeSearchHit> hits) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 C0 搜索证据整理助手。\n");
        builder.append("你的任务不是联网搜索，而是把给定搜索证据整理成适合翻译阶段消费的受控知识卡素材。\n");
        builder.append("只允许依据给定证据输出，不允许补充外部常识，不允许猜测。\n");
        builder.append("如果证据不足、结果明显跑题、或无法形成稳定结论，必须返回 shouldCreateCard=false。\n\n");

        builder.append("【当前 chunk】\n");
        builder.append("chunkId: ").append(chunk.chunk().chunkId()).append("\n");
        builder.append("summary: ").append(nullToEmpty(chunk.summary())).append("\n");
        builder.append("entities: ").append(renderList(chunk.entities())).append("\n");
        builder.append("keyExpressions: ").append(renderList(chunk.keyExpressions())).append("\n");
        builder.append("translationRisks: ").append(renderList(chunk.translationRisks())).append("\n\n");

        builder.append("【知识需求】\n");
        builder.append("cardType: ").append(need.cardType()).append("\n");
        builder.append("queryText: ").append(need.queryText()).append("\n");
        builder.append("keywords: ").append(renderList(need.keywords())).append("\n");
        builder.append("anchorNames: ").append(renderList(need.anchorNames())).append("\n");
        builder.append("originRefs: ").append(renderList(need.originRefs())).append("\n");
        builder.append("reason: ").append(nullToEmpty(need.reason())).append("\n\n");

        builder.append("【证据】\n");
        if (hits == null || hits.isEmpty()) {
            builder.append("无证据\n");
        } else {
            int index = 0;
            for (KnowledgeSearchHit hit : hits) {
                index++;
                builder.append(index).append(". title: ").append(nullToEmpty(hit.title())).append("\n");
                builder.append("   snippet: ").append(nullToEmpty(hit.snippet())).append("\n");
                builder.append("   url: ").append(nullToEmpty(hit.url())).append("\n");
                builder.append("   source: ").append(nullToEmpty(hit.source())).append("\n");
            }
        }

        builder.append("\n请只输出一个 JSON 对象，不要输出 Markdown，不要输出解释。\n");
        builder.append("JSON 必须包含字段：shouldCreateCard,title,summary,translationNotes,keywords,anchorNames,usedEvidenceIndexes,confidence,rejectionReason。\n");
        builder.append("字段要求：\n");
        builder.append("1. shouldCreateCard: boolean，是否应该创建知识卡。\n");
        builder.append("2. title: string，卡片标题；若不建卡可为空字符串。\n");
        builder.append("3. summary: string，基于证据整理出的简短结论，面向翻译，不要写百科。\n");
        builder.append("4. translationNotes: string[]，列出 1-3 条翻译关注点。\n");
        builder.append("5. keywords: string[]，列出建议保留的关键词。\n");
        builder.append("6. anchorNames: string[]，列出建议保留的锚点实体。\n");
        builder.append("7. usedEvidenceIndexes: number[]，只填写实际使用的证据序号，从 1 开始。\n");
        builder.append("8. confidence: string，只能是 HIGH、MEDIUM、LOW。\n");
        builder.append("9. rejectionReason: string，如果 shouldCreateCard=false，说明拒绝原因；否则可为空字符串。\n");
        return builder.toString();
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
