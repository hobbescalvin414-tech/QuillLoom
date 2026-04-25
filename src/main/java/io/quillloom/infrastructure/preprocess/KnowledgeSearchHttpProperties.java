package io.quillloom.infrastructure.preprocess;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * C0 澶栭儴鎼滅储宸ュ叿鐨?HTTP 閰嶇疆銆? */
@ConfigurationProperties(prefix = "quillloom.preprocess.knowledge-search.http")
public class KnowledgeSearchHttpProperties {

    private boolean enabled;
    private String baseUrl = "";
    private String apiKey = "";
    private String apiKeyHeaderName = "Authorization";
    private String apiKeyPrefix = "Bearer ";
    private String queryParamName = "q";
    private String limitParamName = "limit";
    private int timeoutSeconds = 20;
    private int resultLimit = 3;
    private boolean fallbackToHeuristic = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKeyHeaderName() {
        return apiKeyHeaderName;
    }

    public void setApiKeyHeaderName(String apiKeyHeaderName) {
        this.apiKeyHeaderName = apiKeyHeaderName;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public void setApiKeyPrefix(String apiKeyPrefix) {
        this.apiKeyPrefix = apiKeyPrefix;
    }

    public String getQueryParamName() {
        return queryParamName;
    }

    public void setQueryParamName(String queryParamName) {
        this.queryParamName = queryParamName;
    }

    public String getLimitParamName() {
        return limitParamName;
    }

    public void setLimitParamName(String limitParamName) {
        this.limitParamName = limitParamName;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getResultLimit() {
        return resultLimit;
    }

    public void setResultLimit(int resultLimit) {
        this.resultLimit = resultLimit;
    }

    public boolean isFallbackToHeuristic() {
        return fallbackToHeuristic;
    }

    public void setFallbackToHeuristic(boolean fallbackToHeuristic) {
        this.fallbackToHeuristic = fallbackToHeuristic;
    }
}