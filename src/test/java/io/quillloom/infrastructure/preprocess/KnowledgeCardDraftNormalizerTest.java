package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeCardDraftNormalizerTest {

    @Test
    void shouldNormalizeEvidenceIntoStableKnowledgeCardDraft() {
        OrganizedKnowledgeEvidence evidence = new OrganizedKnowledgeEvidence(
                KnowledgeCardType.CULTURAL_BACKGROUND,
                "Victorian church etiquette",
                "Visitors were expected to lower their voice and use formal forms of address.",
                List.of("What are the rules of Victorian church etiquette?", "Alice", "St. Mary parish"),
                List.of("https://example.com/church-etiquette"),
                List.of("chunk:chunk-1#backgroundQuestion:1"),
                "searxng",
                "HIGH"
        );

        KnowledgeCardDraft draft = new KnowledgeCardDraftNormalizer().normalize(
                "chunk-1",
                List.of("Alice", "St. Mary parish"),
                evidence
        );

        assertEquals(List.of("Alice", "St. Mary parish"), draft.anchorNames());
        assertEquals(List.of("https://example.com/church-etiquette"), draft.sourceRefs());
    }
}
