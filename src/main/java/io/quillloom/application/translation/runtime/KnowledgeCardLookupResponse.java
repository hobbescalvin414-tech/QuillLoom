package io.quillloom.application.translation.runtime;

import io.quillloom.domain.knowledge.KnowledgeCard;

import java.util.List;

/**
 * 本地知识库对补卡请求返回的运行期响应。
 */
public record KnowledgeCardLookupResponse(
        String requestId,
        String chunkId,
        List<KnowledgeCard> cards,
        String unresolvedMessage
) {

    public static KnowledgeCardLookupResponse empty(KnowledgeCardLookupRequest request, String unresolvedMessage) {
        return new KnowledgeCardLookupResponse(
                request.requestId(),
                request.chunkId(),
                List.of(),
                unresolvedMessage
        );
    }
}