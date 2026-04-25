package io.quillloom.infrastructure.translation;

import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import io.quillloom.application.translation.model.KnowledgeRetrievalUseCase;
import io.quillloom.application.translation.port.out.KnowledgeRetrievalService;
import io.quillloom.application.translation.port.out.LocalKnowledgeLookupService;
import io.quillloom.application.translation.runtime.KnowledgeCardLookupRequest;
import io.quillloom.application.translation.runtime.KnowledgeCardLookupResponse;
import io.quillloom.application.translation.service.RuleBasedKnowledgeRetrievalService;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.translation.TranslationTaskInput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * D 的本地知识库补卡服务。
 * 当前内部复用统一知识检索层，后续可平滑升级到底层数据库或混合检索实现。
 */
@Component
public class DefaultLocalKnowledgeLookupService implements LocalKnowledgeLookupService {

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public DefaultLocalKnowledgeLookupService() {
        this(new RuleBasedKnowledgeRetrievalService());
    }

    public DefaultLocalKnowledgeLookupService(KnowledgeRetrievalService knowledgeRetrievalService) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    @Override
    public KnowledgeCardLookupResponse lookup(TranslationTaskInput input,
                                              KnowledgeCardLookupRequest request) {
        if (input == null || request == null || request.queryTerms() == null || request.queryTerms().isEmpty()) {
            return request == null ? null : KnowledgeCardLookupResponse.empty(request, "补卡请求为空或缺少查询词");
        }

        List<KnowledgeCard> cards = knowledgeRetrievalService.retrieve(
                input.sourceMaterial().project().projectId(),
                buildQuery(input, request)
        ).cards();
        if (cards.isEmpty()) {
            return KnowledgeCardLookupResponse.empty(request, unresolvedMessage(request));
        }
        return new KnowledgeCardLookupResponse(request.requestId(), request.chunkId(), cards, "");
    }

    private KnowledgeRetrievalQuery buildQuery(TranslationTaskInput input,
                                               KnowledgeCardLookupRequest request) {
        Set<String> excludedCardIds = new LinkedHashSet<>();
        input.executionContextView().relatedKnowledgeCards().forEach(card -> excludedCardIds.add(card.cardId()));
        return new KnowledgeRetrievalQuery(
                KnowledgeRetrievalUseCase.SUPPLEMENTAL_LOOKUP,
                input.sourceMaterial().chunk().chunk().chunkId(),
                request.queryTerms(),
                request.anchors(),
                request.requestedTypes(),
                List.copyOf(excludedCardIds),
                Math.max(0, request.limit()),
                0
        );
    }

    private String unresolvedMessage(KnowledgeCardLookupRequest request) {
        return "本地知识库未命中补卡请求：" + request.reason();
    }
}
