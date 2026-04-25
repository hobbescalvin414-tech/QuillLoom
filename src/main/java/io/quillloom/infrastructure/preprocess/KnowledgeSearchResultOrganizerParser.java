package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class KnowledgeSearchResultOrganizerParser {

    public OrganizedKnowledgeEvidence parse(ChunkAnnotation chunk,
                                            KnowledgeNeed need,
                                            List<KnowledgeSearchHit> hits,
                                            KnowledgeSearchOrganizerLlmResult result) {
        if (result == null || !result.shouldCreateCard()) {
            return null;
        }

        Set<String> anchorNames = new LinkedHashSet<>();
        addAll(anchorNames, need.anchorNames());
        addAll(anchorNames, result.anchorNames());
        addAll(anchorNames, chunk.entities());

        Set<String> evidenceUrls = new LinkedHashSet<>();
        Set<String> providers = new LinkedHashSet<>();
        List<Integer> indexes = result.usedEvidenceIndexes() == null || result.usedEvidenceIndexes().isEmpty()
                ? defaultIndexes(hits)
                : result.usedEvidenceIndexes();
        for (Integer index : indexes) {
            if (index == null) {
                continue;
            }
            int zeroBased = index - 1;
            if (zeroBased < 0 || hits == null || zeroBased >= hits.size()) {
                continue;
            }
            KnowledgeSearchHit hit = hits.get(zeroBased);
            if (hit.url() != null && !hit.url().isBlank()) {
                evidenceUrls.add(hit.url().trim());
            }
            if (hit.source() != null && !hit.source().isBlank()) {
                providers.add(hit.source().trim());
            }
        }

        String content = buildContent(result);
        return new OrganizedKnowledgeEvidence(
                need.cardType(),
                firstNonBlank(result.title(), need.queryText()),
                content,
                List.copyOf(anchorNames),
                List.copyOf(evidenceUrls),
                List.copyOf(need.originRefs()),
                providers.isEmpty() ? "" : String.join(",", providers),
                firstNonBlank(result.confidence(), "MEDIUM")
        );
    }

    private String buildContent(KnowledgeSearchOrganizerLlmResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append(firstNonBlank(result.summary(), ""));
        List<String> notes = safeList(result.translationNotes());
        if (!notes.isEmpty()) {
            builder.append("\n翻译关注点：");
            for (String note : notes) {
                if (note == null || note.isBlank()) {
                    continue;
                }
                builder.append("\n- ").append(note.trim());
            }
        }
        if (result.confidence() != null && !result.confidence().isBlank()) {
            builder.append("\n置信度：").append(result.confidence().trim());
        }
        return builder.toString().trim();
    }

    private List<Integer> defaultIndexes(List<KnowledgeSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            indexes.add(i + 1);
        }
        return indexes;
    }

    private void addAll(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            target.add(value.trim());
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
