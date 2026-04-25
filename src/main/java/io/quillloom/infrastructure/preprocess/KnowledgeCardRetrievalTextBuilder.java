package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCard;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 为知识卡构造受控的检索文本。
 * 仅保留适合检索的核心信息，避免把全部正文和噪声元数据直接送入向量化链路。
 */
@Component
public class KnowledgeCardRetrievalTextBuilder {

    public String build(KnowledgeCard card) {
        if (card == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        appendLine(builder, "知识卡类型", humanizeType(card));
        appendLine(builder, "标题", card.title());
        appendList(builder, "锚点", card.anchorNames());
        appendList(builder, "关键词", card.keywords());
        appendLine(builder, "适用范围", card.scope());
        appendList(builder, "关联分块", card.applicableChunkIds());
        appendLine(builder, "正文", trimContent(card.content()));
        return builder.toString().trim();
    }

    private String humanizeType(KnowledgeCard card) {
        return switch (card.cardType()) {
            case CHARACTER_PROFILE -> "人物卡";
            case TERM_EXPLANATION -> "术语卡";
            case SETTING_ENTRY -> "设定卡";
            case CULTURAL_BACKGROUND -> "文化背景卡";
            case HISTORICAL_BACKGROUND -> "历史背景卡";
            case IMAGERY -> "意象卡";
        };
    }

    private String trimContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 600) {
            return normalized;
        }
        return normalized.substring(0, 600) + "...";
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(label).append("：").append(value.trim()).append("\n");
    }

    private void appendList(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim());
        }
        if (normalized.isEmpty()) {
            return;
        }
        builder.append(label).append("：").append(String.join("、", normalized)).append("\n");
    }
}
