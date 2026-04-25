package io.quillloom.infrastructure.preprocess;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 可配置的 HTTP 搜索客户端。
 * 当前约定外部服务返回结构：{"items":[{"title":"...","snippet":"...","url":"...","source":"...","keywords":[...]}]}。
 */
public class ConfiguredHttpKnowledgeSearchClient implements KnowledgeSearchClient {

    private final RestClient restClient;
    private final KnowledgeSearchHttpProperties properties;

    public ConfiguredHttpKnowledgeSearchClient(RestClient restClient,
                                               KnowledgeSearchHttpProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public List<KnowledgeSearchHit> search(KnowledgeSearchQuery query) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                .queryParam(properties.getQueryParamName(), query.queryText())
                .queryParam(properties.getLimitParamName(), properties.getResultLimit())
                .build(true)
                .toUri();

        SearchResponse response = restClient.get()
                .uri(uri)
                .headers(headers -> applyHeaders(headers))
                .retrieve()
                .body(SearchResponse.class);
        if (response == null || response.items() == null) {
            return List.of();
        }

        List<KnowledgeSearchHit> results = new ArrayList<>();
        for (SearchItem item : response.items()) {
            if (item == null || isBlank(item.title()) || isBlank(item.snippet())) {
                continue;
            }
            results.add(new KnowledgeSearchHit(
                    item.title().trim(),
                    item.snippet().trim(),
                    defaultText(item.url(), ""),
                    defaultText(item.source(), "http-search"),
                    item.keywords() == null ? List.of() : List.copyOf(item.keywords())
            ));
        }
        return List.copyOf(results);
    }

    private void applyHeaders(HttpHeaders headers) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (!isBlank(properties.getApiKey())) {
            headers.set(properties.getApiKeyHeaderName(), properties.getApiKeyPrefix() + properties.getApiKey());
        }
    }

    private String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SearchResponse(List<SearchItem> items) {
    }

    @SuppressWarnings("unused")
    private record SearchItem(String title,
                              String snippet,
                              String url,
                              String source,
                              List<String> keywords,
                              Map<String, Object> extra) {
    }
}