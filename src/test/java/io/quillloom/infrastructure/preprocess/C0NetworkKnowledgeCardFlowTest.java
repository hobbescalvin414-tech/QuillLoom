package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.CoarseChunkPlan;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.KnowledgeEnrichmentBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C0NetworkKnowledgeCardFlowTest {

    @Test
    void shouldBuildKnowledgeCardsFromRemoteSearchEvidence() {
        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor(
                        "chunk-1",
                        1,
                        "block-1",
                        0,
                        220,
                        "Alice studied the rules of Victorian church etiquette before visiting St. Mary parish."
                ),
                "Alice needs church etiquette background before entering the parish.",
                List.of("Alice", "St. Mary parish"),
                List.of("What are the rules of Victorian church etiquette?"),
                List.of("Religious background may affect tone and address."),
                List.of("church etiquette", "parish")
        );

        KnowledgeSearchClient fakeRemoteClient = query -> List.of(
                new KnowledgeSearchHit(
                        "Victorian church etiquette",
                        "Visitors were expected to lower their voice, uncover their head, and use formal forms of address inside the parish church.",
                        "https://example.com/church-etiquette",
                        "searxng",
                        List.of("church", "etiquette", "victorian")
                ),
                new KnowledgeSearchHit(
                        "Parish church customs in the nineteenth century",
                        "Parish visits often signaled class awareness and public respectability in local religious communities.",
                        "https://example.com/parish-customs",
                        "searxng",
                        List.of("parish", "customs", "respectability")
                )
        );

        ToolDrivenKnowledgeEnricher enricher = new ToolDrivenKnowledgeEnricher(
                new NetworkBackedKnowledgeSearchTool(
                        fakeRemoteClient,
                        new KnowledgeSearchResultCondenser()
                ),
                new InMemoryProjectKnowledgeBaseRepository(),
                (chunkAnnotation, targetLanguage) -> List.of(
                        new KnowledgeNeed(
                                KnowledgeCardType.CULTURAL_BACKGROUND,
                                "Victorian church etiquette rules and forms of address",
                                List.of("St. Mary parish"),
                                List.of("Victorian", "church", "etiquette", "address"),
                                List.of("chunk:chunk-1#backgroundQuestion:1"),
                                "需要礼仪背景来稳定称呼与叙述语气。",
                                1
                        )
                ),
                new KnowledgeSearchGate(new KnowledgeSearchGateProperties()),
                new KnowledgeCardDraftNormalizer(),
                new KnowledgeCardMergeService(new KnowledgeCardIdentityResolver()),
                new KnowledgeCardRetrievalTextBuilder(),
                new NoOpKnowledgeEmbeddingService(),
                new NoOpKnowledgeIndexRepository()
        );

        KnowledgeEnrichmentBundle bundle = enricher.enrich(
                new PreprocessBookCommand(
                        "project-c0-network-test",
                        "sample",
                        chunk.chunk().sourceText(),
                        "en",
                        "zh"
                ),
                new GlobalAnalysisBundle(
                        new BookAnalysis("sample synopsis", "outline", "style", List.of(), List.of()),
                        List.of(),
                        CoarseChunkPlan.empty()
                ),
                new ChunkAnnotationBundle(List.of(chunk))
        );

        var cards = bundle.projectKnowledgeBase().cards();
        var candidateTerms = bundle.projectKnowledgeBase().candidateTerms();

        assertFalse(cards.isEmpty());
        assertTrue(cards.stream().allMatch(card -> card.cardType() == KnowledgeCardType.CULTURAL_BACKGROUND));
        assertTrue(cards.stream().anyMatch(card -> card.title().contains("Victorian church etiquette")));
        assertTrue(cards.stream().anyMatch(card -> card.content().contains("lower their voice")));
        assertTrue(cards.stream().anyMatch(card -> card.sourceRefs().contains("https://example.com/church-etiquette")));
        assertTrue(cards.stream().anyMatch(card -> card.sourceRefs().contains("https://example.com/parish-customs")));
        assertTrue(cards.stream().noneMatch(card -> card.sourceRefs().stream().anyMatch(ref -> ref.startsWith("chunk:") || ref.startsWith("source:"))));
        assertTrue(cards.stream().anyMatch(card -> card.anchorNames().contains("Alice")));
        assertTrue(cards.stream().anyMatch(card -> card.anchorNames().contains("St. Mary parish")));
        assertTrue(cards.stream().noneMatch(card -> card.anchorNames().contains("What are the rules of Victorian church etiquette?")));
        assertEquals(List.of("Alice", "St. Mary parish"), candidateTerms.stream().map(term -> term.sourceTerm()).toList());
    }
}
