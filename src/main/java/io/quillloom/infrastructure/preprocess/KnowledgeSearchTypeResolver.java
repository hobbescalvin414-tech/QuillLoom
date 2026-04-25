package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 根据检索种子推断知识卡类型。
 */
@Component
public class KnowledgeSearchTypeResolver {

    public KnowledgeCardType inferFromQuestion(String value) {
        String normalized = normalize(value);
        if (containsAny(normalized, "历史", "朝代", "王朝", "战争", "时代", "history", "dynasty", "war", "era")) {
            return KnowledgeCardType.HISTORICAL_BACKGROUND;
        }
        if (containsAny(normalized, "文化", "礼仪", "宗教", "习俗", "custom", "ritual", "relig", "culture")) {
            return KnowledgeCardType.CULTURAL_BACKGROUND;
        }
        if (containsAny(normalized, "意象", "象征", "metaphor", "symbol", "imagery")) {
            return KnowledgeCardType.IMAGERY;
        }
        if (containsAny(normalized, "术语", "称谓", "translation", "term", "title", "rank")) {
            return KnowledgeCardType.TERM_EXPLANATION;
        }
        return KnowledgeCardType.SETTING_ENTRY;
    }

    public KnowledgeCardType inferFromEntity(String value) {
        String normalized = normalize(value);
        if (containsAny(normalized, "house", "church", "bridge", "city", "town", "府", "桥", "城", "宫")) {
            return KnowledgeCardType.SETTING_ENTRY;
        }
        if (containsAny(normalized, "mr", "mrs", "sir", "lady") || looksLikePersonName(value)) {
            return KnowledgeCardType.CHARACTER_PROFILE;
        }
        return KnowledgeCardType.TERM_EXPLANATION;
    }

    private boolean looksLikePersonName(String entity) {
        if (entity == null || entity.isBlank()) {
            return false;
        }
        return entity.chars().filter(Character::isWhitespace).count() <= 2 && entity.length() <= 40;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}