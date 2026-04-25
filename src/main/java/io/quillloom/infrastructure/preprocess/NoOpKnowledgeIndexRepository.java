package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.model.KnowledgeEmbedding;
import io.quillloom.application.preprocess.model.KnowledgeIndexDocument;
import io.quillloom.application.preprocess.model.KnowledgeIndexMatch;
import io.quillloom.application.preprocess.port.out.KnowledgeIndexRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内存/默认模式下的索引仓储空实现。
 */
@Component
@ConditionalOnProperty(prefix = "quillloom.preprocess.knowledge-base", name = "storage", havingValue = "memory", matchIfMissing = true)
public class NoOpKnowledgeIndexRepository implements KnowledgeIndexRepository {

    @Override
    public void replaceProjectIndex(String projectId,
                                    List<KnowledgeIndexDocument> documents) {
        // 当前默认模式不持久化索引。
    }

    @Override
    public List<KnowledgeIndexMatch> searchSimilar(String projectId,
                                                   KnowledgeEmbedding embedding,
                                                   int limit) {
        return List.of();
    }
}
