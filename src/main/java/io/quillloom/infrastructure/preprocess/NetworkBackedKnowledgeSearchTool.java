package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Knowledge search tool backed by remote search.
 */
public class NetworkBackedKnowledgeSearchTool implements KnowledgeSearchTool {

    private final KnowledgeSearchClient knowledgeSearchClient;
    private final KnowledgeSearchResultOrganizer resultOrganizer;

    public NetworkBackedKnowledgeSearchTool(KnowledgeSearchClient knowledgeSearchClient,
                                            KnowledgeSearchResultOrganizer resultOrganizer) {
        this.knowledgeSearchClient = knowledgeSearchClient;
        this.resultOrganizer = resultOrganizer;
    }

    @Override
    public List<KnowledgeSearchOutcome> search(ChunkAnnotation chunk,
                                               List<KnowledgeNeed> needs) {
        List<KnowledgeSearchOutcome> results = new ArrayList<>();
        Set<String> dedupKeys = new LinkedHashSet<>();
        for (KnowledgeNeed need : needs == null ? List.<KnowledgeNeed>of() : needs) {
            if (need == null) {
                continue;
            }
            KnowledgeSearchQuery query = need.toSearchQuery("PROJECT");
            List<KnowledgeSearchHit> hits = knowledgeSearchClient.search(query);
            KnowledgeSearchOrganizationDecision decision = resultOrganizer.organize(chunk, need, hits);
            if (!decision.accepted()) {
                results.add(new KnowledgeSearchOutcome(
                        need,
                        decision.rawHitCount(),
                        decision.filteredHitCount(),
                        null,
                        decision.rejectionKind(),
                        decision.rejectionReason()
                ));
                continue;
            }
            OrganizedKnowledgeEvidence result = decision.organizedEvidenceOptional().orElseThrow();
            String dedupKey = result.cardType() + "|" + result.title() + "|" + String.join("|", result.evidenceUrls());
            if (dedupKeys.add(dedupKey)) {
                results.add(new KnowledgeSearchOutcome(
                        need,
                        decision.rawHitCount(),
                        decision.filteredHitCount(),
                        result,
                        "",
                        ""
                ));
            }
        }
        return List.copyOf(results);
    }
}
