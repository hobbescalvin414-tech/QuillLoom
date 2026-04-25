package io.quillloom.infrastructure.preprocess;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * C0 联网搜索门控配置。
 */
@ConfigurationProperties(prefix = "quillloom.preprocess.knowledge-search.gate")
public class KnowledgeSearchGateProperties {

    private boolean enabled = true;
    private int maxQueriesPerChunk = 6;
    private boolean requireBackgroundSignal = true;
    private boolean skipWhenCoveredByKnowledgeBase = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxQueriesPerChunk() {
        return maxQueriesPerChunk;
    }

    public void setMaxQueriesPerChunk(int maxQueriesPerChunk) {
        this.maxQueriesPerChunk = maxQueriesPerChunk;
    }

    public boolean isRequireBackgroundSignal() {
        return requireBackgroundSignal;
    }

    public void setRequireBackgroundSignal(boolean requireBackgroundSignal) {
        this.requireBackgroundSignal = requireBackgroundSignal;
    }

    public boolean isSkipWhenCoveredByKnowledgeBase() {
        return skipWhenCoveredByKnowledgeBase;
    }

    public void setSkipWhenCoveredByKnowledgeBase(boolean skipWhenCoveredByKnowledgeBase) {
        this.skipWhenCoveredByKnowledgeBase = skipWhenCoveredByKnowledgeBase;
    }
}
