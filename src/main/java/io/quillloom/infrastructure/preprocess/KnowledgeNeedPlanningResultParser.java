package io.quillloom.infrastructure.preprocess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class KnowledgeNeedPlanningResultParser {

    private static final Pattern NON_SEARCH_PREFIX = Pattern.compile(
            "^(?i)(why\\s+is|why\\s+does|what\\s+does|what\\s+is|how\\s+does|explain|analyze|analysis\\s+of|meaning\\s+of|symbolism\\s+of)\\s+"
    );
    private static final Pattern USE_OF_PATTERN = Pattern.compile("(?i)^(.*?)\\buse\\s+of\\b\\s+(.+?)\\s+\\bas\\b\\s+(.+)$");
    private static final Pattern NON_TOKEN_CHARS = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5\\s-]");
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "a", "an", "the", "of", "for", "to", "in", "on", "at", "by",
            "use", "uses", "using", "used", "meaning", "why",
            "what", "does", "is", "are", "how", "explain", "analysis"
    );
    private static final int MAX_NEEDS = 8;

    private final ObjectMapper objectMapper;

    public KnowledgeNeedPlanningResultParser() {
        this(new ObjectMapper());
    }

    public KnowledgeNeedPlanningResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<KnowledgeNeed> parse(ChunkAnnotation chunk, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("C0 knowledge need planner returned blank result.");
        }
        try {
            KnowledgeNeedPlanningResult result = objectMapper.readValue(raw, KnowledgeNeedPlanningResult.class);
            return toNeeds(chunk, result);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("C0 knowledge need planner returned invalid JSON: " + raw, ex);
        }
    }

    private List<KnowledgeNeed> toNeeds(ChunkAnnotation chunk, KnowledgeNeedPlanningResult result) {
        if (result == null || result.needs() == null || result.needs().isEmpty()) {
            return List.of();
        }
        List<KnowledgeNeed> needs = new ArrayList<>();
        Set<String> seenCoverageKeys = new LinkedHashSet<>();
        Set<String> seenNormalizedQueries = new LinkedHashSet<>();
        for (KnowledgeNeedPlanningResult.KnowledgeNeedPlanningItem item : result.needs()) {
            if (item == null || !item.shouldSearch()) {
                continue;
            }
            String normalizedQueryText = normalizeQueryText(item.queryText(), item.anchorNames(), item.keywords());
            if (normalizedQueryText.isBlank()) {
                continue;
            }
            KnowledgeNeedKind needKind = parseNeedKind(item.needKind());
            KnowledgeNeedSignalSource signalSource = parseSignalSource(item.signalSource(), item.originRefs());
            String coverageKey = normalizeCoverageKey(item.coverageKey(), normalizedQueryText);
            if (!coverageKey.isBlank() && !seenCoverageKeys.add(coverageKey)) {
                continue;
            }
            String normalizedQueryKey = normalizedQueryText.toLowerCase(Locale.ROOT);
            if (!seenNormalizedQueries.add(normalizedQueryKey)) {
                continue;
            }
            needs.add(new KnowledgeNeed(
                    parseCardType(item.cardType()),
                    normalizedQueryText,
                    normalizeAnchors(item.anchorNames(), chunk.entities()),
                    normalizeKeywords(item.keywords(), normalizedQueryText),
                    normalizeOriginRefs(item.originRefs(), chunk.chunk().chunkId()),
                    item.reason() == null ? "" : item.reason().trim(),
                    Math.max(1, item.priority()),
                    needKind,
                    signalSource,
                    coverageKey,
                    normalizeSimpleText(item.searchIntent())
            ));
        }
        return limitWithSignalDiversity(needs);
    }

    private KnowledgeCardType parseCardType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("C0 knowledge need planner missing cardType.");
        }
        try {
            return KnowledgeCardType.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("C0 knowledge need planner returned unknown cardType: " + value, ex);
        }
    }

    private KnowledgeNeedKind parseNeedKind(String value) {
        if (value == null || value.isBlank()) {
            return KnowledgeNeedKind.GENERAL_ENRICHMENT;
        }
        try {
            return KnowledgeNeedKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return KnowledgeNeedKind.GENERAL_ENRICHMENT;
        }
    }

    private KnowledgeNeedSignalSource parseSignalSource(String value, List<String> originRefs) {
        if (value != null && !value.isBlank()) {
            String normalized = value.trim().toUpperCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            normalized = switch (normalized) {
                case "BACKGROUNDQUESTION", "BACKGROUND_QUESTION" -> "BACKGROUND_QUESTION";
                case "TRANSLATIONRISK", "TRANSLATION_RISK" -> "TRANSLATION_RISK";
                case "KEYEXPRESSION", "KEY_EXPRESSION" -> "KEY_EXPRESSION";
                default -> normalized;
            };
            try {
                return KnowledgeNeedSignalSource.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
            }
        }
        String joined = originRefs == null ? "" : String.join(" ", originRefs).toLowerCase(Locale.ROOT);
        if (joined.contains("backgroundquestion")) {
            return KnowledgeNeedSignalSource.BACKGROUND_QUESTION;
        }
        if (joined.contains("translationrisk")) {
            return KnowledgeNeedSignalSource.TRANSLATION_RISK;
        }
        if (joined.contains("keyexpression")) {
            return KnowledgeNeedSignalSource.KEY_EXPRESSION;
        }
        if (joined.contains("entity")) {
            return KnowledgeNeedSignalSource.ENTITY;
        }
        return KnowledgeNeedSignalSource.UNKNOWN;
    }

    private List<String> normalizeAnchors(List<String> values, List<String> fallbackEntities) {
        Set<String> anchors = new LinkedHashSet<>();
        addStableAnchors(anchors, values);
        if (anchors.isEmpty()) {
            addStableAnchors(anchors, fallbackEntities);
        }
        return List.copyOf(anchors);
    }

    private void addStableAnchors(Set<String> anchors, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.contains("?") || trimmed.contains("？")) {
                continue;
            }
            anchors.add(trimmed);
        }
    }

    private List<String> normalizeKeywords(List<String> values, String fallbackQueryText) {
        Set<String> keywords = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    keywords.add(value.trim());
                }
            }
        }
        if (keywords.isEmpty() && fallbackQueryText != null) {
            for (String token : fallbackQueryText.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5\\s]", " ").split("\\s+")) {
                if (token.length() >= 2) {
                    keywords.add(token);
                }
            }
        }
        return List.copyOf(keywords.stream().limit(12).toList());
    }

    private List<String> normalizeOriginRefs(List<String> values, String chunkId) {
        Set<String> refs = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    refs.add(value.trim());
                }
            }
        }
        if (refs.isEmpty()) {
            refs.add("chunk:" + chunkId + "#planner");
        }
        return List.copyOf(refs);
    }

    private String normalizeCoverageKey(String rawCoverageKey, String normalizedQueryText) {
        String value = normalizeSimpleText(rawCoverageKey);
        if (value.isBlank()) {
            value = normalizeSimpleText(normalizedQueryText);
        }
        value = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5\\s-]", " ")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return value;
    }

    private String normalizeSimpleText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String normalizeQueryText(String raw,
                                      List<String> anchorNames,
                                      List<String> keywords) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String query = raw.trim()
                .replace('‘', ' ')
                .replace('’', ' ')
                .replace('“', ' ')
                .replace('”', ' ')
                .replace('\'', ' ')
                .replace('"', ' ');
        query = collapseWhitespace(query);
        query = rewriteUseOfPattern(query);
        query = NON_SEARCH_PREFIX.matcher(query).replaceFirst("");
        query = NON_TOKEN_CHARS.matcher(query).replaceAll(" ");
        query = collapseWhitespace(query);

        Set<String> orderedTerms = new LinkedHashSet<>();
        addMeaningfulPhrase(orderedTerms, query);
        if (orderedTerms.size() < 3) {
            addMeaningfulPhrase(orderedTerms, firstNonBlank(first(anchorNames), null));
        }
        if (orderedTerms.size() < 4) {
            addMeaningfulPhrase(orderedTerms, first(keywords));
        }
        if (orderedTerms.size() < 5) {
            addMeaningfulPhrase(orderedTerms, second(keywords));
        }

        String normalized = collapseWhitespace(String.join(" ", orderedTerms));
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64).trim();
            int lastSpace = normalized.lastIndexOf(' ');
            if (lastSpace >= 24) {
                normalized = normalized.substring(0, lastSpace);
            }
        }
        return normalized;
    }

    private String rewriteUseOfPattern(String query) {
        var matcher = USE_OF_PATTERN.matcher(query);
        if (!matcher.matches()) {
            return query;
        }
        String leading = collapseWhitespace(matcher.group(1));
        String subject = collapseWhitespace(matcher.group(2));
        String target = collapseWhitespace(matcher.group(3));
        return collapseWhitespace(subject + " " + leading + " " + target);
    }

    private void addMeaningfulPhrase(Set<String> orderedTerms, String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return;
        }
        for (String token : collapseWhitespace(phrase).split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            String normalized = token.trim();
            if (QUERY_STOP_WORDS.contains(normalized.toLowerCase(Locale.ROOT))) {
                continue;
            }
            orderedTerms.add(normalized);
        }
    }

    private String first(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private String second(List<String> values) {
        if (values == null || values.size() < 2) {
            return null;
        }
        return values.get(1);
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback;
    }

    private String collapseWhitespace(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private List<KnowledgeNeed> limitWithSignalDiversity(List<KnowledgeNeed> needs) {
        if (needs.isEmpty()) {
            return List.of();
        }
        List<KnowledgeNeed> sorted = needs.stream()
                .sorted(Comparator.comparingInt(KnowledgeNeed::priority))
                .toList();
        List<KnowledgeNeed> selected = new ArrayList<>();
        Set<KnowledgeNeedSignalSource> selectedSignals = new LinkedHashSet<>();
        for (KnowledgeNeed need : sorted) {
            if (need.signalSource() != KnowledgeNeedSignalSource.UNKNOWN && selectedSignals.add(need.signalSource())) {
                selected.add(need);
                if (selected.size() >= MAX_NEEDS) {
                    return List.copyOf(selected);
                }
            }
        }
        Map<KnowledgeNeedSignalSource, Integer> perSignalCounts = new java.util.LinkedHashMap<>();
        for (KnowledgeNeed selectedNeed : selected) {
            perSignalCounts.merge(selectedNeed.signalSource(), 1, Integer::sum);
        }
        for (KnowledgeNeed need : sorted) {
            if (selected.contains(need)) {
                continue;
            }
            int currentCount = perSignalCounts.getOrDefault(need.signalSource(), 0);
            if (currentCount >= 3) {
                continue;
            }
            selected.add(need);
            perSignalCounts.put(need.signalSource(), currentCount + 1);
            if (selected.size() >= MAX_NEEDS) {
                break;
            }
        }
        return List.copyOf(selected.stream()
                .sorted(Comparator.comparingInt(KnowledgeNeed::priority))
                .toList());
    }
}
