package io.quillloom.application.preprocess.port.out;

import io.quillloom.application.preprocess.model.KnowledgeEmbedding;

/**
 * 知识卡 embedding 生成端口。
 */
public interface KnowledgeEmbeddingService {

    KnowledgeEmbedding embed(String text);
}
