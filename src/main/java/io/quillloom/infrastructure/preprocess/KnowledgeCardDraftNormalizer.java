package io.quillloom.infrastructure.preprocess;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class KnowledgeCardDraftNormalizer {

    public KnowledgeCardDraft normalize(String chunkId,
                                        List<String> chunkEntities,
                                        OrganizedKnowledgeEvidence evidence) {
        if (evidence == null) {
            return null;
        }
        return new KnowledgeCardDraft(
                evidence.cardType(),
                defaultText(evidence.title(), "Untitled knowledge card"),
                defaultText(evidence.content(), ""),
                mergeStableAnchors(chunkEntities, evidence.anchorNames()),
                filterUrls(evidence.evidenceUrls()),
                List.of(chunkId)
        );
    }

    private List<String> mergeStableAnchors(List<String> chunkEntities, List<String> evidenceAnchors) {
        Set<String> anchors = new LinkedHashSet<>();
        addStableAnchors(anchors, evidenceAnchors);
        addStableAnchors(anchors, chunkEntities);
        return List.copyOf(anchors);
    }

    private void addStableAnchors(Set<String> anchors, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.contains("?") || trimmed.contains("？")) {
                continue;
            }
            anchors.add(trimmed);
        }
    }

    private List<String> filterUrls(List<String> refs) {
        Set<String> urls = new LinkedHashSet<>();
        if (refs != null) {
            for (String ref : refs) {
                if (ref != null && ref.startsWith("http://") || ref != null && ref.startsWith("https://")) {
                    urls.add(ref.trim());
                }
            }
        }
        return List.copyOf(urls);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
