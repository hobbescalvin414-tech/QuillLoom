package io.quillloom.application.preprocess.model;

/**
 * 知识索引相似召回结果。
 */
public record KnowledgeIndexMatch(
        String cardId,
        double similarityScore
) {
}
