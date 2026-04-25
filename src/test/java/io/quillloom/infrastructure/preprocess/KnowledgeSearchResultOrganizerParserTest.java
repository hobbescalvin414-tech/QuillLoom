package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchResultOrganizerParserTest {

    private final KnowledgeSearchResultOrganizerParser parser = new KnowledgeSearchResultOrganizerParser();

    @Test
    void shouldMapLlmOutputIntoOrganizedEvidence() {
        ChunkAnnotation chunk = createChunk();
        KnowledgeNeed need = new KnowledgeNeed(
                KnowledgeCardType.TERM_EXPLANATION,
                "DogHa Tutorial",
                List.of("DogHa Tutorial"),
                List.of("DogHa", "tutorial"),
                List.of("query:DogHa Tutorial"),
                "需要识别 proper noun",
                1
        );
        List<KnowledgeSearchHit> hits = List.of(
                new KnowledgeSearchHit("DogHa Tutorial", "The site contains illustrated tutorials.", "https://quanxiaoha.com", "google", List.of()),
                new KnowledgeSearchHit("DogHa Tutorial Collection", "Includes basic and advanced lessons.", "https://example.com/tutorial", "bing", List.of())
        );
        KnowledgeSearchOrganizerLlmResult result = new KnowledgeSearchOrganizerLlmResult(
                true,
                "DogHa Tutorial",
                "Search evidence suggests this term refers to a tutorial site or tutorial collection.",
                List.of("Treat it as a proper name and keep naming consistent."),
                List.of("DogHa", "tutorial"),
                List.of("DogHa Tutorial"),
                List.of(1),
                "HIGH",
                ""
        );

        OrganizedKnowledgeEvidence organized = parser.parse(chunk, need, hits, result);

        assertNotNull(organized);
        assertEquals("DogHa Tutorial", organized.title());
        assertTrue(organized.content().contains("Search evidence suggests"));
        assertTrue(organized.content().contains("proper name"));
        assertTrue(organized.evidenceUrls().contains("https://quanxiaoha.com"));
        assertEquals(KnowledgeCardType.TERM_EXPLANATION, organized.cardType());
        assertEquals("google", organized.searchProvider());
    }

    @Test
    void shouldReturnNullWhenLlmRejectsCardCreation() {
        OrganizedKnowledgeEvidence organized = parser.parse(
                createChunk(),
                new KnowledgeNeed(KnowledgeCardType.SETTING_ENTRY, "old church", List.of("old church"), List.of(), List.of(), "", 1),
                List.of(),
                new KnowledgeSearchOrganizerLlmResult(false, "", "", List.of(), List.of(), List.of(), List.of(), "LOW", "not enough evidence")
        );

        assertNull(organized);
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
