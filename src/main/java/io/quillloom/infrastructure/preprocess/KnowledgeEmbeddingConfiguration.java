package io.quillloom.infrastructure.preprocess;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.quillloom.application.preprocess.port.out.KnowledgeEmbeddingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * 知识卡 embedding 服务装配。
 * 默认关闭；显式启用后切换为 OpenAI 兼容 embedding 模型。
 */
@Configuration
@EnableConfigurationProperties(KnowledgeBaseEmbeddingProperties.class)
public class KnowledgeEmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "quillloom.preprocess.knowledge-base.embedding", name = "enabled", havingValue = "true")
    public EmbeddingModel knowledgeBaseEmbeddingModel(KnowledgeBaseEmbeddingProperties properties) {
        validate(properties);
        return OpenAiEmbeddingModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogResponses())
                .build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "quillloom.preprocess.knowledge-base.embedding", name = "enabled", havingValue = "true")
    public KnowledgeEmbeddingService openAiCompatibleKnowledgeEmbeddingService(EmbeddingModel knowledgeBaseEmbeddingModel,
                                                                               KnowledgeBaseEmbeddingProperties properties) {
        return new OpenAiCompatibleKnowledgeEmbeddingService(knowledgeBaseEmbeddingModel, properties.getModelName());
    }

    private void validate(KnowledgeBaseEmbeddingProperties properties) {
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("启用知识库 embedding 时必须提供 quillloom.preprocess.knowledge-base.embedding.base-url。");
        }
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("启用知识库 embedding 时必须提供 quillloom.preprocess.knowledge-base.embedding.api-key。");
        }
        if (isBlank(properties.getModelName())) {
            throw new IllegalStateException("启用知识库 embedding 时必须提供 quillloom.preprocess.knowledge-base.embedding.model-name。");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
