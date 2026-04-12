package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCard;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在 C0 建库阶段识别并合并可增量扩充的知识卡。
 */
@Component
public class KnowledgeCardMergeService {

    private final KnowledgeCardIdentityResolver identityResolver;

    public KnowledgeCardMergeService(KnowledgeCardIdentityResolver identityResolver) {
        this.identityResolver = identityResolver;
    }

    public KnowledgeCardMergeService() {
        this(new KnowledgeCardIdentityResolver());
    }

    public KnowledgeCard mergeInto(List<KnowledgeCard> existingCards,
                                   KnowledgeCard incomingCard) {
        if (incomingCard == null) {
            return null;
        }
        KnowledgeCard matched = findMergeTarget(existingCards, incomingCard);
        if (matched == null) {
            return incomingCard;
        }
        return merge(matched, incomingCard);
    }

    public KnowledgeCard findMergeTarget(List<KnowledgeCard> existingCards,
                                         KnowledgeCard incomingCard) {
        String incomingIdentity = identityResolver.resolveIdentityKey(incomingCard);
        if (existingCards == null || existingCards.isEmpty() || incomingIdentity.isBlank()) {
            return null;
        }
        for (KnowledgeCard existingCard : existingCards) {
            if (existingCard == null) {
                continue;
            }
            if (incomingIdentity.equals(identityResolver.resolveIdentityKey(existingCard))) {
                return existingCard;
            }
        }
        return null;
    }

    private KnowledgeCard merge(KnowledgeCard existingCard,
                                KnowledgeCard incomingCard) {
        return new KnowledgeCard(
                existingCard.cardId(),
                existingCard.cardType(),
                preferTitle(existingCard, incomingCard),
                mergeContent(existingCard.content(), incomingCard.content()),
                mergeList(existingCard.keywords(), incomingCard.keywords()),
                mergeList(existingCard.anchorNames(), incomingCard.anchorNames()),
                mergeList(existingCard.sourceRefs(), incomingCard.sourceRefs()),
                existingCard.scope(),
                mergeList(existingCard.applicableChunkIds(), incomingCard.applicableChunkIds()),
                mergeMetadata(existingCard.metadata(), incomingCard.metadata())
        );
    }

    private String preferTitle(KnowledgeCard existingCard,
                               KnowledgeCard incomingCard) {
        if (existingCard.title() != null && !existingCard.title().isBlank()) {
            return existingCard.title();
        }
        return incomingCard.title();
    }

    private String mergeContent(String existingContent,
                                String incomingContent) {
        String left = existingContent == null ? "" : existingContent.trim();
        String right = incomingContent == null ? "" : incomingContent.trim();
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank() || left.contains(right)) {
            return left;
        }
        if (right.contains(left)) {
            return right;
        }
        return left + "\n\n【增量补充】\n" + right;
    }

    private List<String> mergeList(List<String> left,
                                   List<String> right) {
        Set<String> values = new LinkedHashSet<>();
        addAll(values, left);
        addAll(values, right);
        return List.copyOf(values);
    }

    private void addAll(Set<String> target,
                        List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            target.add(value.trim());
        }
    }

    private java.util.Map<String, Object> mergeMetadata(java.util.Map<String, Object> left,
                                                        java.util.Map<String, Object> right) {
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (left != null) {
            merged.putAll(left);
        }
        if (right != null) {
            right.forEach(merged::putIfAbsent);
        }
        return java.util.Map.copyOf(merged);
    }
}
