package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.port.out.KnowledgeEmbeddingService;
import io.quillloom.application.preprocess.port.out.KnowledgeIndexRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeInfrastructureFallbackConfiguration {

    @Bean
    @ConditionalOnMissingBean(KnowledgeEmbeddingService.class)
    public KnowledgeEmbeddingService noOpKnowledgeEmbeddingService() {
        return new NoOpKnowledgeEmbeddingService();
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeIndexRepository.class)
    public KnowledgeIndexRepository noOpKnowledgeIndexRepository() {
        return new NoOpKnowledgeIndexRepository();
    }
}
