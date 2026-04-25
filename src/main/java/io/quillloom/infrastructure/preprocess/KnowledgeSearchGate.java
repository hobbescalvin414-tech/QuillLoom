package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 控制 C0 是否放行规划出的知识需求。
 */
@Component
public class KnowledgeSearchGate {

    private final KnowledgeSearchGateProperties properties;

    public KnowledgeSearchGate(KnowledgeSearchGateProperties properties) {
        this.properties = properties;
    }

    public List<KnowledgeSearchQuery> filterQueries(ChunkAnnotation chunk,
                                                    ProjectKnowledgeBase knowledgeBase,
                                                    List<KnowledgeSearchQuery> plannedQueries) {
        if (plannedQueries == null || plannedQueries.isEmpty()) {
            return List.of();
        }
        if (!properties.isEnabled()) {
            return limit(plannedQueries);
        }
        if (!isSearchWorthy(chunk)) {
            return List.of();
        }

        List<KnowledgeSearchQuery> allowed = new ArrayList<>();
        for (KnowledgeSearchQuery query : plannedQueries) {
            if (query == null) {
                continue;
            }
            if (properties.isSkipWhenCoveredByKnowledgeBase() && isCoveredByKnowledgeBase(query, knowledgeBase)) {
                continue;
            }
            allowed.add(query);
            if (allowed.size() >= Math.max(0, properties.getMaxQueriesPerChunk())) {
                break;
            }
        }
        return List.copyOf(allowed);
    }

    public List<KnowledgeNeed> filterNeeds(ChunkAnnotation chunk,
                                           ProjectKnowledgeBase knowledgeBase,
                                           List<KnowledgeNeed> plannedNeeds) {
        if (plannedNeeds == null || plannedNeeds.isEmpty()) {
            return List.of();
        }
        List<KnowledgeNeed> sortedNeeds = plannedNeeds.stream()
                .filter(need -> need != null && need.queryText() != null && !need.queryText().isBlank())
                .sorted(Comparator.comparingInt(KnowledgeNeed::priority))
                .toList();
        List<KnowledgeNeed> uncoveredNeeds = new ArrayList<>();
        for (KnowledgeNeed need : sortedNeeds) {
            if (need == null || need.queryText() == null || need.queryText().isBlank()) {
                continue;
            }
            if (properties.isSkipWhenCoveredByKnowledgeBase() && isCoveredByKnowledgeBase(need, knowledgeBase)) {
                continue;
            }
            uncoveredNeeds.add(need);
        }

        int budget = Math.max(0, properties.getMaxQueriesPerChunk());
        if (budget == 0 || uncoveredNeeds.isEmpty()) {
            return List.of();
        }

        List<KnowledgeNeed> allowed = new ArrayList<>();
        Map<KnowledgeNeedSignalSource, Integer> perSignalCounts = new LinkedHashMap<>();
        for (KnowledgeNeed need : uncoveredNeeds) {
            if (need.signalSource() == KnowledgeNeedSignalSource.UNKNOWN) {
                continue;
            }
            if (perSignalCounts.containsKey(need.signalSource())) {
                continue;
            }
            allowed.add(need);
            perSignalCounts.put(need.signalSource(), 1);
            if (allowed.size() >= budget) {
                return List.copyOf(allowed);
            }
        }
        for (KnowledgeNeed need : uncoveredNeeds) {
            if (allowed.contains(need)) {
                continue;
            }
            int perSignalCount = perSignalCounts.getOrDefault(need.signalSource(), 0);
            if (perSignalCount >= 2) {
                continue;
            }
            allowed.add(need);
            perSignalCounts.put(need.signalSource(), perSignalCount + 1);
            if (allowed.size() >= budget) {
                break;
            }
        }
        return List.copyOf(allowed);
    }

    private boolean isSearchWorthy(ChunkAnnotation chunk) {
        if (!properties.isRequireBackgroundSignal()) {
            return true;
        }
        boolean hasBackgroundQuestions = chunk.backgroundQuestions() != null && !chunk.backgroundQuestions().isEmpty();
        boolean hasBackgroundRisk = chunk.translationRisks() != null && chunk.translationRisks().stream().anyMatch(this::containsBackgroundSignal);
        boolean hasImagerySignal = chunk.keyExpressions() != null && chunk.keyExpressions().size() >= 2;
        return hasBackgroundQuestions || hasBackgroundRisk || hasImagerySignal;
    }

    private boolean isCoveredByKnowledgeBase(KnowledgeSearchQuery query,
                                             ProjectKnowledgeBase knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.cards().isEmpty()) {
            return false;
        }
        String normalizedQuery = normalize(query.queryText());
        for (KnowledgeCard card : knowledgeBase.cards()) {
            if (card == null || card.cardType() != query.cardType()) {
                continue;
            }
            if (containsMatch(card.anchorNames(), normalizedQuery) || containsMatch(card.keywords(), normalizedQuery)) {
                return true;
            }
            if (containsText(card.title(), normalizedQuery) || containsText(card.content(), normalizedQuery)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCoveredByKnowledgeBase(KnowledgeNeed need,
                                             ProjectKnowledgeBase knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.cards().isEmpty()) {
            return false;
        }
        String normalizedQuery = normalize(need.queryText());
        for (KnowledgeCard card : knowledgeBase.cards()) {
            if (card == null || card.cardType() != need.cardType()) {
                continue;
            }
            if (containsMatch(card.keywords(), normalizedQuery)) {
                return true;
            }
            if (containsText(card.title(), normalizedQuery) || containsText(card.content(), normalizedQuery)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsBackgroundSignal(String value) {
        String normalized = normalize(value);
        return normalized.contains("culture")
                || normalized.contains("history")
                || normalized.contains("relig")
                || normalized.contains("ritual")
                || normalized.contains("background")
                || normalized.contains("背景")
                || normalized.contains("文化")
                || normalized.contains("历史")
                || normalized.contains("礼")
                || normalized.contains("宗教")
                || normalized.contains("典故");
    }

    private boolean containsMatch(List<String> values, String normalizedQuery) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            String normalizedValue = normalize(value);
            if (normalizedValue.contains(normalizedQuery) || normalizedQuery.contains(normalizedValue)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsText(String value, String normalizedQuery) {
        return normalize(value).contains(normalizedQuery);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private List<KnowledgeSearchQuery> limit(List<KnowledgeSearchQuery> queries) {
        return List.copyOf(queries.stream().limit(Math.max(0, properties.getMaxQueriesPerChunk())).toList());
    }
}
