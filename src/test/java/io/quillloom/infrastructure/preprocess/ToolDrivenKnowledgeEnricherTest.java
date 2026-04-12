package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.CoarseChunkPlan;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.PersonAliasHint;
import io.quillloom.infrastructure.preprocess.intrinsic.IntrinsicAliasState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDrivenKnowledgeEnricherTest {

    @Test
    void shouldKeepExternalFlowAndAlsoAttachIntrinsicCharacterCard() {
        ToolDrivenKnowledgeEnricher enricher = new ToolDrivenKnowledgeEnricher(
                (chunk, needs) -> List.of(),
                new InMemoryProjectKnowledgeBaseRepository(),
                (chunk, targetLanguage) -> List.of(),
                new KnowledgeSearchGate(new KnowledgeSearchGateProperties()),
                new KnowledgeCardDraftNormalizer(),
                new KnowledgeCardMergeService(new KnowledgeCardIdentityResolver()),
                new KnowledgeCardRetrievalTextBuilder(),
                new NoOpKnowledgeEmbeddingService(),
                new NoOpKnowledgeIndexRepository()
        );

        ChunkAnnotation chunk1 = new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, 120, "Louki stood by the window."),
                "Louki appears in the room.",
                List.of("Louki"),
                List.of(),
                List.of(),
                List.of("window"),
                List.of()
        );
        ChunkAnnotation chunk2 = new ChunkAnnotation(
                new ChunkDescriptor("chunk-2", 2, "block-1", 121, 260, "Jacqueline lowered her voice. Louki looked away."),
                "Jacqueline is referred to again.",
                List.of("Louki", "Jacqueline"),
                List.of(),
                List.of(),
                List.of("lowered her voice"),
                List.of(new PersonAliasHint(
                        List.of("Louki", "Jacqueline"),
                        "same-person-name-variant",
                        "HIGH",
                        "同一人物在相邻片段中以两个称呼出现"
                ))
        );

        var bundle = enricher.enrich(
                new PreprocessBookCommand("project-intrinsic-c0", "sample", "Louki stood by the window.", "fr", "zh"),
                new GlobalAnalysisBundle(
                        new BookAnalysis("sample synopsis", "outline", "style", List.of(), List.of()),
                        List.of(),
                        CoarseChunkPlan.empty()
                ),
                new ChunkAnnotationBundle(List.of(chunk1, chunk2))
        );

        var cards = bundle.projectKnowledgeBase().cards();

        assertFalse(cards.isEmpty());
        assertTrue(cards.stream().anyMatch(card -> card.cardType() == KnowledgeCardType.CHARACTER_PROFILE));
        assertTrue(cards.stream().anyMatch(card -> card.title().contains("Louki")));
        var intrinsicCard = cards.stream()
                .filter(card -> card.cardType() == KnowledgeCardType.CHARACTER_PROFILE)
                .findFirst()
                .orElseThrow();
        assertEquals("PROJECT", intrinsicCard.scope());
        assertTrue(intrinsicCard.anchorNames().contains("Louki"));
        assertTrue(intrinsicCard.anchorNames().contains("Jacqueline"));
        assertEquals("Louki", intrinsicCard.metadata().get("canonicalName"));
        assertEquals(IntrinsicAliasState.SUSPECTED_ALIAS.name(), intrinsicCard.metadata().get("aliasState"));
    }
}
