package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 默认搜索工具实现。
 * 当前先基于 chunk 标注生成受控搜索结果，后续可平滑替换为真实联网搜索实现。
 */
@Component
public class HeuristicKnowledgeSearchTool implements KnowledgeSearchTool {

    private static final String PROJECT_SCOPE = "PROJECT";

    private final KnowledgeSearchTypeResolver typeResolver;

    public HeuristicKnowledgeSearchTool(KnowledgeSearchTypeResolver typeResolver) {
        this.typeResolver = typeResolver;
    }

    public HeuristicKnowledgeSearchTool() {
        this(new KnowledgeSearchTypeResolver());
    }

    @Override
    public List<KnowledgeSearchOutcome> search(ChunkAnnotation chunk,
                                               List<KnowledgeNeed> needs) {
        throw new IllegalStateException("HeuristicKnowledgeSearchTool 已退役，C0 不允许回退到 heuristic 搜索。");
    }

    private List<KnowledgeSearchResult> searchFromQueries(ChunkAnnotation chunk,
                                                          List<KnowledgeSearchQuery> queries) {
        List<KnowledgeSearchResult> results = new ArrayList<>();
        int index = 0;
        for (KnowledgeSearchQuery query : queries) {
            if (query == null || query.queryText() == null || query.queryText().isBlank()) {
                continue;
            }
            index++;
            results.add(new KnowledgeSearchResult(
                    query.cardType(),
                    buildTitle(query.cardType(), query.queryText()),
                    "围绕当前 chunk 的受控搜索结果：" + query.queryText(),
                    mergeKeywords(chunk, query),
                    mergeAnchorNames(chunk, query),
                    mergeSourceRefs(query, chunk, index),
                    PROJECT_SCOPE
            ));
        }
        return List.copyOf(results);
    }

    private List<KnowledgeSearchQuery> buildDefaultQueries(ChunkAnnotation chunk) {
        List<KnowledgeSearchQuery> queries = new ArrayList<>();
        int questionIndex = 0;
        for (String question : safeList(chunk.backgroundQuestions())) {
            if (question == null || question.isBlank()) {
                continue;
            }
            questionIndex++;
            KnowledgeCardType type = typeResolver.inferFromQuestion(question);
            queries.add(new KnowledgeSearchQuery(
                    type,
                    question.trim(),
                    buildKeywords(chunk, question),
                    buildAnchorNames(chunk, question),
                    List.of("chunk:" + chunk.chunk().chunkId() + "#backgroundQuestion:" + questionIndex),
                    PROJECT_SCOPE
            ));
        }
        int expressionIndex = 0;
        for (String expression : safeList(chunk.keyExpressions())) {
            if (expression == null || expression.isBlank()) {
                continue;
            }
            expressionIndex++;
            queries.add(new KnowledgeSearchQuery(
                    KnowledgeCardType.IMAGERY,
                    expression.trim(),
                    buildKeywords(chunk, expression),
                    buildAnchorNames(chunk, expression),
                    List.of("chunk:" + chunk.chunk().chunkId() + "#keyExpression:" + expressionIndex),
                    PROJECT_SCOPE
            ));
        }
        int entityIndex = 0;
        for (String entity : safeList(chunk.entities())) {
            if (entity == null || entity.isBlank()) {
                continue;
            }
            entityIndex++;
            KnowledgeCardType type = typeResolver.inferFromEntity(entity);
            queries.add(new KnowledgeSearchQuery(
                    type,
                    entity.trim(),
                    buildKeywords(chunk, entity),
                    buildAnchorNames(chunk, entity),
                    List.of("chunk:" + chunk.chunk().chunkId() + "#entity:" + entityIndex),
                    PROJECT_SCOPE
            ));
        }
        return List.copyOf(queries);
    }

    private List<String> mergeKeywords(ChunkAnnotation chunk, KnowledgeSearchQuery query) {
        Set<String> values = new LinkedHashSet<>(safeList(query.keywords()));
        safeList(chunk.entities()).forEach(value -> addTokens(values, value));
        return List.copyOf(values.stream().limit(12).toList());
    }

    private List<String> mergeAnchorNames(ChunkAnnotation chunk, KnowledgeSearchQuery query) {
        Set<String> values = new LinkedHashSet<>(safeList(query.anchorNames()));
        safeList(chunk.entities()).forEach(entity -> {
            if (entity != null && !entity.isBlank()) {
                values.add(entity);
            }
        });
        return List.copyOf(values.stream().limit(8).toList());
    }

    private List<String> mergeSourceRefs(KnowledgeSearchQuery query,
                                         ChunkAnnotation chunk,
                                         int index) {
        Set<String> refs = new LinkedHashSet<>(safeList(query.sourceRefs()));
        if (refs.isEmpty()) {
            refs.add("chunk:" + chunk.chunk().chunkId() + "#query:" + index);
        }
        return List.copyOf(refs);
    }

    private String buildTitle(KnowledgeCardType type, String seed) {
        return switch (type) {
            case HISTORICAL_BACKGROUND -> "历史背景：" + abbreviate(seed);
            case CULTURAL_BACKGROUND -> "文化背景：" + abbreviate(seed);
            case IMAGERY -> "特定意象：" + abbreviate(seed);
            case SETTING_ENTRY -> "设定条目：" + abbreviate(seed);
            case TERM_EXPLANATION -> "术语解释：" + abbreviate(seed);
            case CHARACTER_PROFILE -> "人物信息：" + abbreviate(seed);
        };
    }

    private List<String> buildKeywords(ChunkAnnotation chunk, String seed) {
        Set<String> values = new LinkedHashSet<>();
        addTokens(values, seed);
        addTokens(values, chunk.summary());
        safeList(chunk.entities()).forEach(value -> addTokens(values, value));
        return List.copyOf(values.stream().limit(12).toList());
    }

    private List<String> buildAnchorNames(ChunkAnnotation chunk, String seed) {
        Set<String> values = new LinkedHashSet<>();
        if (seed != null && !seed.isBlank()) {
            values.add(seed);
        }
        safeList(chunk.entities()).forEach(entity -> {
            if (entity != null && !entity.isBlank()) {
                values.add(entity);
            }
        });
        return List.copyOf(values.stream().limit(8).toList());
    }

    private void addTokens(Set<String> values, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String normalized = raw.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5\\s]", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                values.add(token);
            }
        }
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 24) {
            return value == null ? "" : value;
        }
        return value.substring(0, 24) + "...";
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
