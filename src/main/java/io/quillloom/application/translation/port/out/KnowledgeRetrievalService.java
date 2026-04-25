package io.quillloom.application.translation.port.out;

import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import io.quillloom.application.translation.model.KnowledgeRetrievalResult;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;

/**
 * 项目知识库统一检索端口。
 * 当前既支持基于已加载知识库快照检索，也支持按项目标识在内部加载知识库后检索。
 * 后续可平滑演进到底层数据库、向量检索或混合检索实现。
 */
public interface KnowledgeRetrievalService {

    KnowledgeRetrievalResult retrieve(String projectId,
                                      ProjectKnowledgeBase preferredKnowledgeBase,
                                      KnowledgeRetrievalQuery query);

    default KnowledgeRetrievalResult retrieve(ProjectKnowledgeBase knowledgeBase,
                                              KnowledgeRetrievalQuery query) {
        if (knowledgeBase == null) {
            return new KnowledgeRetrievalResult(java.util.List.of());
        }
        return retrieve(knowledgeBase.projectId(), knowledgeBase, query);
    }

    default KnowledgeRetrievalResult retrieve(String projectId,
                                              KnowledgeRetrievalQuery query) {
        return retrieve(projectId, null, query);
    }
}
