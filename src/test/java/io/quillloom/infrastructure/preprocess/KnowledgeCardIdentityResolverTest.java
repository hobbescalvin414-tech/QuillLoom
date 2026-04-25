package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeCardIdentityResolverTest {

    @Test
    void shouldNormalizeCharacterAliasesIntoSameIdentityKey() {
        KnowledgeCard alice = new KnowledgeCard(
                "card-alice-1",
                KnowledgeCardType.CHARACTER_PROFILE,
                "Alice profile",
                "Alice appears reserved.",
                List.of("Alice"),
                List.of("Alice"),
                List.of("chunk:1"),
                "PROJECT",
                List.of("chunk-1")
        );
        KnowledgeCard missAlice = new KnowledgeCard(
                "card-alice-2",
                KnowledgeCardType.CHARACTER_PROFILE,
                "Miss Alice",
                "Miss Alice appears distant.",
                List.of("Miss Alice"),
                List.of("Miss Alice"),
                List.of("chunk:2"),
                "PROJECT",
                List.of("chunk-2")
        );

        KnowledgeCardIdentityResolver resolver = new KnowledgeCardIdentityResolver();

        assertEquals(resolver.resolveIdentityKey(alice), resolver.resolveIdentityKey(missAlice));
        assertTrue(resolver.resolveIdentityKey(alice).startsWith("CHARACTER_PROFILE::alice"));
    }

    @Test
    void shouldReturnBlankIdentityForUnsupportedTypes() {
        KnowledgeCard imagery = new KnowledgeCard(
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

        KnowledgeCardIdentityResolver resolver = new KnowledgeCardIdentityResolver();

        assertEquals("", resolver.resolveIdentityKey(imagery));
    }
}