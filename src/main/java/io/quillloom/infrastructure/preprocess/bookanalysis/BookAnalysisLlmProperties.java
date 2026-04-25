package io.quillloom.infrastructure.preprocess.bookanalysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quillloom.preprocess.book-analysis.llm")
public class BookAnalysisLlmProperties {

    private boolean enabled;
    private String baseUrl = "";
    private String apiKey = "";
    private String modelName = "";
    private boolean logRequests;
    private boolean logResponses;
    private int baseTimeoutSeconds = 240;
    private int timeoutStepChars = 8000;
    private int timeoutStepSeconds = 60;
    private int maxTimeoutSeconds = 900;

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

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public boolean isLogRequests() {
        return logRequests;
    }

    public void setLogRequests(boolean logRequests) {
        this.logRequests = logRequests;
    }

    public boolean isLogResponses() {
        return logResponses;
    }

    public void setLogResponses(boolean logResponses) {
        this.logResponses = logResponses;
    }

    public int getBaseTimeoutSeconds() {
        return baseTimeoutSeconds;
    }

    public void setBaseTimeoutSeconds(int baseTimeoutSeconds) {
        this.baseTimeoutSeconds = baseTimeoutSeconds;
    }

    public int getTimeoutStepChars() {
        return timeoutStepChars;
    }

    public void setTimeoutStepChars(int timeoutStepChars) {
        this.timeoutStepChars = timeoutStepChars;
    }

    public int getTimeoutStepSeconds() {
        return timeoutStepSeconds;
    }

    public void setTimeoutStepSeconds(int timeoutStepSeconds) {
        this.timeoutStepSeconds = timeoutStepSeconds;
    }

    public int getMaxTimeoutSeconds() {
        return maxTimeoutSeconds;
    }

    public void setMaxTimeoutSeconds(int maxTimeoutSeconds) {
        this.maxTimeoutSeconds = maxTimeoutSeconds;
    }
}
