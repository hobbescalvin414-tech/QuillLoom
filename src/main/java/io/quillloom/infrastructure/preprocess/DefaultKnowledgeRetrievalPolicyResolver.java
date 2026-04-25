package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.translation.model.KnowledgeRetrievalPolicy;
import io.quillloom.application.translation.model.KnowledgeRetrievalUseCase;
import io.quillloom.application.translation.port.out.KnowledgeRetrievalPolicyResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 默认检索策略解析器。
 */
@Component
@EnableConfigurationProperties(KnowledgeRetrievalProperties.class)
public class DefaultKnowledgeRetrievalPolicyResolver implements KnowledgeRetrievalPolicyResolver {

    private final KnowledgeRetrievalProperties properties;

    public DefaultKnowledgeRetrievalPolicyResolver(KnowledgeRetrievalProperties properties) {
        this.properties = properties;
    }

    public DefaultKnowledgeRetrievalPolicyResolver() {
        this(new KnowledgeRetrievalProperties());
    }

    @Override
    public KnowledgeRetrievalPolicy resolve(KnowledgeRetrievalUseCase useCase) {
        KnowledgeRetrievalProperties.Scenario scenario = useCase == KnowledgeRetrievalUseCase.SUPPLEMENTAL_LOOKUP
                ? properties.getSupplementalLookup()
                : properties.getAssembly();
        return new KnowledgeRetrievalPolicy(
                scenario.getDirectChunkMatchWeight(),
                scenario.getExactAnchorMatchWeight(),
                scenario.getQueryAnchorWeight(),
                scenario.getQueryKeywordWeight(),
                scenario.getTitleTextWeight(),
                scenario.getContentTextWeight(),
                scenario.getAnchorHintWeight(),
                scenario.getAnchorTitleWeight(),
                scenario.getPreferredTypeWeight(),
                scenario.getVectorSimilarityScale(),
                scenario.getVectorPreferredTypeBonus(),
                scenario.getVectorDirectChunkBonus(),
                scenario.getVectorRecallMultiplier(),
                scenario.getDefaultLimit(),
                scenario.getDefaultPerTypeLimit()
        );
    }
}
