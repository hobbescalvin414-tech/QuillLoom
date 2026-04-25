package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 为可增量合并的知识卡解析稳定身份键。
 * 该身份键独立于存储实现，后续可直接映射到数据库索引或唯一键策略。
 */
@Component
public class KnowledgeCardIdentityResolver {

    public String resolveIdentityKey(KnowledgeCard card) {
        if (card == null || !supportsIncrementalMerge(card.cardType())) {
            return "";
        }
        String primaryAnchor = resolvePrimaryAnchor(card);
        if (primaryAnchor.isBlank()) {
            return "";
        }
        return card.cardType().name() + "::" + primaryAnchor;
    }

    public boolean supportsIncrementalMerge(KnowledgeCardType cardType) {
        return cardType == KnowledgeCardType.CHARACTER_PROFILE
                || cardType == KnowledgeCardType.SETTING_ENTRY
                || cardType == KnowledgeCardType.TERM_EXPLANATION;
    }

    private String resolvePrimaryAnchor(KnowledgeCard card) {
        String anchorIdentity = selectCanonicalCandidate(card.anchorNames());
        if (!anchorIdentity.isBlank()) {
            return anchorIdentity;
        }
        return selectCanonicalCandidate(card.keywords());
    }

    private String selectCanonicalCandidate(List<String> rawCandidates) {
        List<String> candidates = new ArrayList<>();
        if (rawCandidates != null) {
            candidates.addAll(rawCandidates);
        }
        return candidates.stream()
                .map(this::normalizeAnchor)
                .filter(value -> !value.isBlank())
                .sorted()
                .findFirst()
                .orElse("");
    }

    private String normalizeAnchor(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("^(mr|mrs|ms|miss|sir|lady)\\s+", "");
        normalized = normalized.replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-");
        normalized = normalized.replaceAll("-+", "-");
        normalized = normalized.replaceAll("^-|-$", "");
        return normalized;
    }
}