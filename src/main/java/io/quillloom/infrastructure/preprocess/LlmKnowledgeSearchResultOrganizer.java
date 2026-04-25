package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LlmKnowledgeSearchResultOrganizer implements KnowledgeSearchResultOrganizer {

    private static final Set<String> TITLE_BLOCKLIST = Set.of(
            "guestbook",
            "search",
            "search results",
            "forum",
            "openrice",
            "restaurant"
    );

    private final KnowledgeSearchOrganizerPromptRenderer promptRenderer;
    private final LlmKnowledgeSearchResultOrganizerClient llmClient;
    private final KnowledgeSearchResultOrganizerParser parser;

    public LlmKnowledgeSearchResultOrganizer(KnowledgeSearchOrganizerPromptRenderer promptRenderer,
                                             LlmKnowledgeSearchResultOrganizerClient llmClient,
                                             KnowledgeSearchResultOrganizerParser parser) {
        this.promptRenderer = promptRenderer;
        this.llmClient = llmClient;
        this.parser = parser;
    }

    @Override
    public KnowledgeSearchOrganizationDecision organize(ChunkAnnotation chunk,
                                                        KnowledgeNeed need,
                                                        List<KnowledgeSearchHit> hits) {
        List<KnowledgeSearchHit> filteredHits = prefilter(hits);
        if (filteredHits.isEmpty()) {
            return KnowledgeSearchOrganizationDecision.rejected(
                    need,
                    sizeOf(hits),
                    0,
                    "NO_FILTERED_HITS",
                    "no search hits survived organizer prefilter"
            );
        }
        String prompt = promptRenderer.render(chunk, need, filteredHits);
        KnowledgeSearchOrganizerLlmResult result = llmClient.generate(prompt);
        OrganizedKnowledgeEvidence organized = parser.parse(chunk, need, filteredHits, result);
        if (organized != null) {
            return KnowledgeSearchOrganizationDecision.accepted(sizeOf(hits), filteredHits.size(), organized);
        }
        return KnowledgeSearchOrganizationDecision.rejected(
                need,
                sizeOf(hits),
                filteredHits.size(),
                "ORGANIZER_REJECTED",
                result == null ? "unknown" : result.rejectionReason()
        );
    }

    private int sizeOf(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private List<KnowledgeSearchHit> prefilter(List<KnowledgeSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<KnowledgeSearchHit> filtered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (KnowledgeSearchHit hit : hits) {
            if (hit == null || isBlank(hit.title()) || isBlank(hit.snippet())) {
                continue;
            }
            if (isBlockedTitle(hit.title())) {
                continue;
            }
            if (hit.snippet().trim().length() < 12) {
                continue;
            }
            String key = hit.title().trim() + "|" + hit.url();
            if (!seen.add(key)) {
                continue;
            }
            filtered.add(hit);
            if (filtered.size() >= 3) {
                break;
            }
        }
        return List.copyOf(filtered);
    }

    private boolean isBlockedTitle(String title) {
        String normalized = title.toLowerCase(Locale.ROOT);
        for (String blocked : TITLE_BLOCKLIST) {
            if (normalized.contains(blocked)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
