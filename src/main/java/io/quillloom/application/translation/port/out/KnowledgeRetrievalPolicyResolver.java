package io.quillloom.application.translation.port.out;

import io.quillloom.application.translation.model.KnowledgeRetrievalPolicy;
import io.quillloom.application.translation.model.KnowledgeRetrievalUseCase;

/**
 * 统一知识检索策略解析端口。
 */
public interface KnowledgeRetrievalPolicyResolver {

    KnowledgeRetrievalPolicy resolve(KnowledgeRetrievalUseCase useCase);
}
