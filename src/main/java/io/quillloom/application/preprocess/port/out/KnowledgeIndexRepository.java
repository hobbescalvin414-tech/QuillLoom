package io.quillloom.application.preprocess.port.out;

import io.quillloom.application.preprocess.model.KnowledgeEmbedding;
import io.quillloom.application.preprocess.model.KnowledgeIndexDocument;
import io.quillloom.application.preprocess.model.KnowledgeIndexMatch;

import java.util.List;

/**
 * 知识索引存储端口。
 */
public interface KnowledgeIndexRepository {

    void replaceProjectIndex(String projectId,
                             List<KnowledgeIndexDocument> documents);

    List<KnowledgeIndexMatch> searchSimilar(String projectId,
                                            KnowledgeEmbedding embedding,
                                            int limit);
}
