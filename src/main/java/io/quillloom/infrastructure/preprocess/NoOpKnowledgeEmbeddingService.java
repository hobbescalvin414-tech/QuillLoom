package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.model.KnowledgeEmbedding;
import io.quillloom.application.preprocess.port.out.KnowledgeEmbeddingService;

/**
 * embedding 服务占位实现。
 * 默认模式和未启用 embedding 时使用。
 */
public class NoOpKnowledgeEmbeddingService implements KnowledgeEmbeddingService {

    @Override
    public KnowledgeEmbedding embed(String text) {
        return new KnowledgeEmbedding(java.util.List.of(), "", "");
    }
}
