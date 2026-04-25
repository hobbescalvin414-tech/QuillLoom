package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SearxngKnowledgeSearchClientSmokeTest {

    @Test
    void shouldCallLocalSearxngWhenSmokeTestIsEnabled() {
        Assumptions.assumeTrue(Boolean.getBoolean("quillloom.test.searxng.enabled"),
                "未启用 SearXNG 联网烟雾测试。设置 -Dquillloom.test.searxng.enabled=true 后执行。");

        KnowledgeSearchSearxngProperties properties = new KnowledgeSearchSearxngProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:8888/search");
        properties.setFormat("json");
        properties.setLanguage("zh-CN");
        properties.setTimeoutSeconds(20);
        properties.setMaxResults(3);

        SearxngKnowledgeSearchClient searchClient = new SearxngKnowledgeSearchClient(new OkHttpClient(), properties);
        List<KnowledgeSearchHit> hits = searchClient.search(new KnowledgeSearchQuery(
                KnowledgeCardType.CULTURAL_BACKGROUND,
                "维多利亚时期教堂礼仪是什么意思",
                List.of("维多利亚", "教堂", "礼仪"),
                List.of("church etiquette"),
                List.of("smoke:test"),
                "PROJECT"
        ));

        assertNotNull(hits);
        assertFalse(hits.isEmpty());
    }
}