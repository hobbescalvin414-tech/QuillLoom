package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmKnowledgeSearchResultOrganizerTest {

    @Test
    void shouldReturnRejectedDecisionWhenOrganizerDeclinesCardCreation() {
        LlmKnowledgeSearchResultOrganizer organizer = new LlmKnowledgeSearchResultOrganizer(
                new KnowledgeSearchOrganizerPromptRenderer(),
                prompt -> new KnowledgeSearchOrganizerLlmResult(
                        false,
                        "",
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "LOW",
                        "not enough evidence"
                ),
                new KnowledgeSearchResultOrganizerParser()
        );

        KnowledgeSearchOrganizationDecision decision = organizer.organize(
                createChunk(),
                new KnowledgeNeed(
                        KnowledgeCardType.CULTURAL_BACKGROUND,
                        "Dans le cafe French literary chapter title",
                        List.of("Dans le cafe"),
                        List.of("Dans le cafe", "chapter title"),
                        List.of("chunk:chunk-2#backgroundQuestion:1"),
                        "需要标题文化背景",
                        1
                ),
                List.of(
                        new KnowledgeSearchHit(
                                "Relevant evidence",
                                "This source mentions literary title conventions in French fiction.",
                                "https://example.com/title-1",
                                "google",
                                List.of("title", "French")
                        ),
                        new KnowledgeSearchHit(
                                "Noise",
                                "short",
                                "https://example.com/noise",
                                "google",
                                List.of()
                        )
                )
        );

        assertFalse(decision.accepted());
        assertEquals("not enough evidence", decision.rejectionReason());
        assertEquals("ORGANIZER_REJECTED", decision.rejectionKind());
        assertEquals(2, decision.rawHitCount());
        assertEquals(1, decision.filteredHitCount());
        assertTrue(decision.organizedEvidenceOptional().isEmpty());
    }

    private ChunkAnnotation createChunk() {
        return new ChunkAnnotation(
                new ChunkDescriptor("chunk-2", 2, 121, 240, "Dans le cafe"),
                "The title may need literary context.",
                List.of("Dans le cafe", "Patrick Modiano"),
                List.of("Why is this chapter titled Dans le cafe?"),
                List.of("Title wording may carry literary convention."),
                List.of("Dans le cafe")
        );
    }
}
