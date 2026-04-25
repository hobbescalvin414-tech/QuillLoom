package io.quillloom.infrastructure.preprocess;

import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * C0 搜索工具装配。当前链路要求显式启用 SearXNG，不允许回退到 heuristic。
 */
@Configuration
@EnableConfigurationProperties({KnowledgeSearchSearxngProperties.class, KnowledgeSearchGateProperties.class})
public class KnowledgeSearchToolConfiguration {

    @Bean
    public OkHttpClient knowledgeSearchOkHttpClient(KnowledgeSearchSearxngProperties properties) {
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds()));
        return new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
    }

    @Bean
    @Primary
    public KnowledgeSearchTool activeKnowledgeSearchTool(KnowledgeSearchSearxngProperties properties,
                                                         OkHttpClient knowledgeSearchOkHttpClient,
                                                         KnowledgeSearchResultOrganizer resultOrganizer) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("C0 network knowledge search 已改为不可回退主链，必须显式启用 quillloom.preprocess.knowledge-search.searxng.enabled=true。");
        }
        validate(properties);
        return new NetworkBackedKnowledgeSearchTool(
                new SearxngKnowledgeSearchClient(knowledgeSearchOkHttpClient, properties),
                resultOrganizer
        );
    }

    private void validate(KnowledgeSearchSearxngProperties properties) {
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("Missing quillloom.preprocess.knowledge-search.searxng.base-url");
        }
        if (properties.getMaxResults() <= 0) {
            throw new IllegalStateException("SearXNG max-results must be greater than 0");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
