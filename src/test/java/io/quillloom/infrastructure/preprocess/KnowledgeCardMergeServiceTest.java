package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeCardMergeServiceTest {

    @Test
    void shouldMergeCharacterCardsWithSameNormalizedIdentity() {
        KnowledgeCard existing = new KnowledgeCard(
                "card-alice",
                KnowledgeCardType.CHARACTER_PROFILE,
                "Alice profile",
                "Alice is restrained and observant.",
                List.of("Alice", "restrained"),
                List.of("Alice"),
                List.of("chunk:1"),
                "PROJECT",
                List.of("chunk-1")
        );
        KnowledgeCard incoming = new KnowledgeCard(
                "card-alice-2",
                KnowledgeCardType.CHARACTER_PROFILE,
                "Miss Alice in chapter two",
                "Alice also appears socially distant in chapter two.",
                List.of("Miss Alice", "distant"),
                List.of("Miss Alice"),
                List.of("chunk:2"),
                "PROJECT",
                List.of("chunk-2")
        );

        KnowledgeCardMergeService service = new KnowledgeCardMergeService(new KnowledgeCardIdentityResolver());

        KnowledgeCard target = service.findMergeTarget(List.of(existing), incoming);
        KnowledgeCard merged = service.mergeInto(List.of(existing), incoming);

        assertNotNull(target);
        assertEquals("card-alice", target.cardId());
        assertEquals("card-alice", merged.cardId());
        assertTrue(merged.content().contains("Alice is restrained and observant."));
        assertTrue(merged.content().contains("【增量补充】"));
        assertTrue(merged.content().contains("Alice also appears socially distant in chapter two."));
        assertTrue(merged.anchorNames().contains("Miss Alice"));
        assertTrue(merged.sourceRefs().contains("chunk:2"));
        assertTrue(merged.applicableChunkIds().contains("chunk-2"));
    }

    @Test
    void shouldNotMergeImageryCardsEvenWithSameAnchor() {
        KnowledgeCard existing = new KnowledgeCard(
                "imagery-1",
                KnowledgeCardType.IMAGERY,
                "Bell imagery",
                "Bell suggests ritual distance.",
                List.of("bell"),
                List.of("old church bell"),
                List.of("chunk:1"),
                "PROJECT",
                List.of("chunk-1")
        );
        KnowledgeCard incoming = new KnowledgeCard(
                "imagery-2",
                KnowledgeCardType.IMAGERY,
                "Bell imagery again",
                "Bell also signals communal memory.",
                List.of("bell"),
                List.of("old church bell"),
                List.of("chunk:2"),
                "PROJECT",
                List.of("chunk-2")
        );

        KnowledgeCardMergeService service = new KnowledgeCardMergeService(new KnowledgeCardIdentityResolver());

        assertNull(service.findMergeTarget(List.of(existing), incoming));
        assertEquals("imagery-2", service.mergeInto(List.of(existing), incoming).cardId());
    }
}