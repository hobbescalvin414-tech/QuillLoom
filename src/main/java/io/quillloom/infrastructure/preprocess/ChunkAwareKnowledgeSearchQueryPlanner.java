package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 根据 B 的 chunk 标注生成 C0 的联网检索任务。
 */
@Component
public class ChunkAwareKnowledgeSearchQueryPlanner {

    private final KnowledgeSearchTypeResolver typeResolver;

    public ChunkAwareKnowledgeSearchQueryPlanner(KnowledgeSearchTypeResolver typeResolver) {
        this.typeResolver = typeResolver;
    }

    public List<KnowledgeSearchQuery> plan(ChunkAnnotation chunk) {
        List<KnowledgeSearchQuery> queries = new ArrayList<>();
        int questionIndex = 0;
        for (String question : safeList(chunk.backgroundQuestions())) {
            if (question == null || question.isBlank()) {
                continue;
            }
            questionIndex++;
            KnowledgeCardType cardType = typeResolver.inferFromQuestion(question);
            queries.add(new KnowledgeSearchQuery(
                    cardType,
                    question.trim(),
                    buildKeywords(chunk, question),
                    buildAnchorNames(chunk, question),
                    List.of("chunk:" + chunk.chunk().chunkId() + "#backgroundQuestion:" + questionIndex),
                    "PROJECT"
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
                    "PROJECT"
            ));
        }
        int entityIndex = 0;
        for (String entity : safeList(chunk.entities())) {
            if (entity == null || entity.isBlank()) {
                continue;
            }
            entityIndex++;
            KnowledgeCardType cardType = typeResolver.inferFromEntity(entity);
            queries.add(new KnowledgeSearchQuery(
                    cardType,
                    entity.trim(),
                    buildKeywords(chunk, entity),
                    buildAnchorNames(chunk, entity),
                    List.of("chunk:" + chunk.chunk().chunkId() + "#entity:" + entityIndex),
                    "PROJECT"
            ));
        }
        return List.copyOf(queries);
    }

    public List<KnowledgeSearchQuery> planEligibleQueries(ChunkAnnotation chunk,
                                                          ProjectKnowledgeBase knowledgeBase,
                                                          KnowledgeSearchGate gate) {
        return gate.filterQueries(chunk, knowledgeBase, plan(chunk));
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
            values.add(seed.trim());
        }
        safeList(chunk.entities()).forEach(entity -> {
            if (entity != null && !entity.isBlank()) {
                values.add(entity.trim());
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

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}