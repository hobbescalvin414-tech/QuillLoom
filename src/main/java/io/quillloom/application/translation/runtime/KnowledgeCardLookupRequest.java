package io.quillloom.application.translation.runtime;

import io.quillloom.domain.knowledge.KnowledgeCardType;

import java.util.List;

/**
 * D 在单 chunk 运行期向本地知识库发出的补卡请求。
 */
public record KnowledgeCardLookupRequest(
        String requestId,
        String chunkId,
        KnowledgeGapReason reason,
        List<String> queryTerms,
        List<KnowledgeCardType> requestedTypes,
        List<String> anchors,
        int limit
) {
}