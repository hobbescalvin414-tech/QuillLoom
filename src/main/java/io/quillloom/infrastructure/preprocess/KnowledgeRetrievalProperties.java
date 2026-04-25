package io.quillloom.infrastructure.preprocess;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 统一知识检索策略配置。
 */
@ConfigurationProperties(prefix = "quillloom.preprocess.knowledge-base.retrieval")
public class KnowledgeRetrievalProperties {

    private final Scenario assembly = new Scenario();
    private final Scenario supplementalLookup = new Scenario();

    public Scenario getAssembly() {
        return assembly;
    }

    public Scenario getSupplementalLookup() {
        return supplementalLookup;
    }

    public static class Scenario {
        private int directChunkMatchWeight = 100;
        private int exactAnchorMatchWeight = 40;
        private int queryAnchorWeight = 12;
        private int queryKeywordWeight = 8;
        private int titleTextWeight = 6;
        private int contentTextWeight = 4;
        private int anchorHintWeight = 10;
        private int anchorTitleWeight = 5;
        private int preferredTypeWeight = 16;
        private int vectorSimilarityScale = 30;
        private int vectorPreferredTypeBonus = 8;
        private int vectorDirectChunkBonus = 12;
        private int vectorRecallMultiplier = 3;
        private int defaultLimit = 6;
        private int defaultPerTypeLimit = 2;

        public int getDirectChunkMatchWeight() { return directChunkMatchWeight; }
        public void setDirectChunkMatchWeight(int value) { this.directChunkMatchWeight = value; }
        public int getExactAnchorMatchWeight() { return exactAnchorMatchWeight; }
        public void setExactAnchorMatchWeight(int value) { this.exactAnchorMatchWeight = value; }
        public int getQueryAnchorWeight() { return queryAnchorWeight; }
        public void setQueryAnchorWeight(int value) { this.queryAnchorWeight = value; }
        public int getQueryKeywordWeight() { return queryKeywordWeight; }
        public void setQueryKeywordWeight(int value) { this.queryKeywordWeight = value; }
        public int getTitleTextWeight() { return titleTextWeight; }
        public void setTitleTextWeight(int value) { this.titleTextWeight = value; }
        public int getContentTextWeight() { return contentTextWeight; }
        public void setContentTextWeight(int value) { this.contentTextWeight = value; }
        public int getAnchorHintWeight() { return anchorHintWeight; }
        public void setAnchorHintWeight(int value) { this.anchorHintWeight = value; }
        public int getAnchorTitleWeight() { return anchorTitleWeight; }
        public void setAnchorTitleWeight(int value) { this.anchorTitleWeight = value; }
        public int getPreferredTypeWeight() { return preferredTypeWeight; }
        public void setPreferredTypeWeight(int value) { this.preferredTypeWeight = value; }
        public int getVectorSimilarityScale() { return vectorSimilarityScale; }
        public void setVectorSimilarityScale(int value) { this.vectorSimilarityScale = value; }
        public int getVectorPreferredTypeBonus() { return vectorPreferredTypeBonus; }
        public void setVectorPreferredTypeBonus(int value) { this.vectorPreferredTypeBonus = value; }
        public int getVectorDirectChunkBonus() { return vectorDirectChunkBonus; }
        public void setVectorDirectChunkBonus(int value) { this.vectorDirectChunkBonus = value; }
        public int getVectorRecallMultiplier() { return vectorRecallMultiplier; }
        public void setVectorRecallMultiplier(int value) { this.vectorRecallMultiplier = value; }
        public int getDefaultLimit() { return defaultLimit; }
        public void setDefaultLimit(int value) { this.defaultLimit = value; }
        public int getDefaultPerTypeLimit() { return defaultPerTypeLimit; }
        public void setDefaultPerTypeLimit(int value) { this.defaultPerTypeLimit = value; }
    }
}
