package io.quillloom.application.preprocess.model;

/**
 * 知识索引文档。
 */
public record KnowledgeIndexDocument(
        String projectId,
        String cardId,
        String retrievalText,
        KnowledgeEmbedding embedding
) {
}
