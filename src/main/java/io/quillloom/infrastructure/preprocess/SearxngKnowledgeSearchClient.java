package io.quillloom.infrastructure.preprocess;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SearXNG JSON search client for C0 knowledge search.
 */
public class SearxngKnowledgeSearchClient implements KnowledgeSearchClient {

    private final OkHttpClient okHttpClient;
    private final KnowledgeSearchSearxngProperties properties;
    private final ObjectMapper objectMapper;

    public SearxngKnowledgeSearchClient(OkHttpClient okHttpClient,
                                        KnowledgeSearchSearxngProperties properties) {
        this(okHttpClient, properties, new ObjectMapper());
    }

    SearxngKnowledgeSearchClient(OkHttpClient okHttpClient,
                                 KnowledgeSearchSearxngProperties properties,
                                 ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<KnowledgeSearchHit> search(KnowledgeSearchQuery query) {
        Request request = new Request.Builder()
                .url(buildUrl(query))
                .headers(buildHeaders())
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("SearXNG request failed, status=" + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                return List.of();
            }
            SearxngSearchResponse searchResponse = objectMapper.readValue(body.string(), SearxngSearchResponse.class);
            if (searchResponse.results() == null || searchResponse.results().isEmpty()) {
                return List.of();
            }
            List<KnowledgeSearchHit> hits = new ArrayList<>();
            for (SearxngSearchResult result : searchResponse.results()) {
                if (result == null || isBlank(result.title()) || isBlank(result.content())) {
                    continue;
                }
                hits.add(new KnowledgeSearchHit(
                        result.title().trim(),
                        result.content().trim(),
                        defaultText(result.url(), ""),
                        resolveSource(result),
                        List.of()
                ));
                if (hits.size() >= Math.max(1, properties.getMaxResults())) {
                    break;
                }
            }
            return List.copyOf(hits);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to call SearXNG", exception);
        }
    }

    private HttpUrl buildUrl(KnowledgeSearchQuery query) {
        HttpUrl baseUrl = HttpUrl.parse(properties.getBaseUrl());
        if (baseUrl == null) {
            throw new IllegalStateException("Invalid SearXNG base-url: " + properties.getBaseUrl());
        }
        HttpUrl.Builder builder = baseUrl.newBuilder()
                .addQueryParameter("q", defaultText(query.queryText(), ""))
                .addQueryParameter("format", defaultText(properties.getFormat(), "json"))
                .addQueryParameter("pageno", String.valueOf(Math.max(1, properties.getPageNo())));
        if (!isBlank(properties.getLanguage())) {
            builder.addQueryParameter("language", properties.getLanguage().trim());
        }
        String categories = joinList(properties.getCategories());
        if (!categories.isBlank()) {
            builder.addQueryParameter("categories", categories);
        }
        String engines = joinList(properties.getEngines());
        if (!engines.isBlank()) {
            builder.addQueryParameter("engines", engines);
        }
        return builder.build();
    }

    private Headers buildHeaders() {
        Headers.Builder headersBuilder = new Headers.Builder();
        headersBuilder.add("Accept", "application/json");
        if (!isBlank(properties.getApiKey())) {
            headersBuilder.add(defaultText(properties.getApiKeyHeaderName(), "Authorization"),
                    defaultText(properties.getApiKeyPrefix(), "") + properties.getApiKey());
        }
        return headersBuilder.build();
    }

    private String resolveSource(SearxngSearchResult result) {
        if (result.engines() != null && !result.engines().isEmpty()) {
            String joined = result.engines().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.joining(","));
            if (!joined.isBlank()) {
                return joined;
            }
        }
        return defaultText(result.engine(), "searxng");
    }

    private String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(","));
    }

    private String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearxngSearchResponse(List<SearxngSearchResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearxngSearchResult(String url,
                                       String title,
                                       String content,
                                       String engine,
                                       List<String> engines,
                                       @JsonProperty("publishedDate") String publishedDate,
                                       Double score,
                                       String category) {
    }
}