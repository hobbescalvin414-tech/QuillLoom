package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkBackedKnowledgeSearchToolTest {

    @Test
    void shouldCondenseRemoteHitsIntoSingleStructuredEvidencePerNeed() {
        ChunkAnnotation chunk = createChunk();

        NetworkBackedKnowledgeSearchTool tool = new NetworkBackedKnowledgeSearchTool(
                query -> List.of(
                        new KnowledgeSearchHit(
                                "Victorian church etiquette",
                                "Church etiquette affects forms of address and narrative distance.",
                                "https://example.com/church",
                                "tavily",
                                List.of("church", "etiquette")
                        ),
                        new KnowledgeSearchHit(
                                "Religious symbolism in parish life",
                                "Parish symbols can signal class position and communal duty.",
                                "https://example.com/parish",
                                "tavily",
                                List.of("parish", "symbolism")
                        )
                ),
                new KnowledgeSearchResultCondenser()
        );

        List<KnowledgeSearchOutcome> results = tool.search(chunk, List.of(
                new KnowledgeNeed(
                        KnowledgeCardType.CULTURAL_BACKGROUND,
                        "Victorian church etiquette rules and forms of address",
                        List.of("old church"),
                        List.of("church", "etiquette"),
                        List.of("chunk:chunk-1#backgroundQuestion:1"),
                        "需要礼仪背景",
                        1
                )
        ));

        assertFalse(results.isEmpty());
        assertEquals(1, results.size());
        assertTrue(results.get(0).accepted());
        assertTrue(results.get(0).organizedEvidenceOptional().orElseThrow().content().contains("Church etiquette affects"));
        assertTrue(results.get(0).organizedEvidenceOptional().orElseThrow().content().contains("Parish symbols can signal"));
        assertTrue(results.get(0).organizedEvidenceOptional().orElseThrow().evidenceUrls().contains("https://example.com/church"));
    }

    @Test
    void shouldThrowWhenRemoteSearchFails() {
        ChunkAnnotation chunk = createChunk();

        NetworkBackedKnowledgeSearchTool tool = new NetworkBackedKnowledgeSearchTool(
                query -> {
                    throw new IllegalStateException("remote failed");
                },
                new KnowledgeSearchResultCondenser()
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> tool.search(chunk, List.of(
                new KnowledgeNeed(
                        KnowledgeCardType.CULTURAL_BACKGROUND,
                        "Victorian church etiquette rules and forms of address",
                        List.of("old church"),
                        List.of("church", "etiquette"),
                        List.of("chunk:chunk-1#backgroundQuestion:1"),
                        "需要礼仪背景",
                        1
                )
        )));

        assertEquals("remote failed", exception.getMessage());
    }

    @Test
    void shouldReturnRejectedOutcomeInsteadOfThrowingWhenOrganizerDeclines() {
        ChunkAnnotation chunk = createChunk();

        NetworkBackedKnowledgeSearchTool tool = new NetworkBackedKnowledgeSearchTool(
                query -> List.of(
                        new KnowledgeSearchHit(
                                "Le Conde hotel",
                                "A hotel listing unrelated to the literary cafe.",
                                "https://example.com/hotel",
                                "google",
                                List.of("hotel")
                        )
                ),
                (annotation, need, hits) -> KnowledgeSearchOrganizationDecision.rejected(
                        need,
                        hits == null ? 0 : hits.size(),
                        1,
                        "ENTITY_AMBIGUOUS",
                        "same-name venue mismatch"
                )
        );

        List<KnowledgeSearchOutcome> results = tool.search(chunk, List.of(
                new KnowledgeNeed(
                        KnowledgeCardType.CULTURAL_BACKGROUND,
                        "Le Conde Paris cafe history",
                        List.of("Le Conde"),
                        List.of("Le Conde", "Paris", "cafe"),
                        List.of("chunk:chunk-1#backgroundQuestion:1"),
                        "需要咖啡馆背景",
                        1
                )
        ));

        assertEquals(1, results.size());
        assertFalse(results.get(0).accepted());
        assertEquals("ENTITY_AMBIGUOUS", results.get(0).rejectionKind());
        assertEquals("same-name venue mismatch", results.get(0).rejectionReason());
    }

    private ChunkAnnotation createChunk() {
        return new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, 0, 120, "Alice met Bob in the old church."),
                "Characters meet in a church.",
                List.of("Alice", "old church"),
                List.of("What does this church mean in local religious culture?"),
                List.of("religious context may affect tone"),
                List.of("old church bell")
        );
    }
}
