package io.quillloom.application.translation.service;

import io.quillloom.application.preprocess.model.KnowledgeEmbedding;
import io.quillloom.application.preprocess.model.KnowledgeIndexDocument;
import io.quillloom.application.preprocess.model.KnowledgeIndexMatch;
import io.quillloom.application.preprocess.port.out.KnowledgeIndexRepository;
import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.application.translation.model.KnowledgeRetrievalPolicy;
import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import io.quillloom.application.translation.model.KnowledgeRetrievalUseCase;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.infrastructure.preprocess.DefaultKnowledgeRetrievalPolicyResolver;
import io.quillloom.infrastructure.preprocess.KnowledgeRetrievalProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedKnowledgeRetrievalServiceTest {

    @Test
    void shouldKeepKeywordRetrievalWorkingWhenVectorRecallIsUnavailable() {
        ProjectKnowledgeBase knowledgeBase = new ProjectKnowledgeBase(
                "project-1",
                List.of(
                        new KnowledgeCard(
                                "card-alice",
                                KnowledgeCardType.CHARACTER_PROFILE,
                                "Alice",
                                "Alice is a main character.",
                                List.of("Alice"),
                                List.of("Alice"),
                                List.of(),
                                "PROJECT",
                                List.of("chunk-1")
                        ),
                        new KnowledgeCard(
                                "card-house",
                                KnowledgeCardType.SETTING_ENTRY,
                                "Old House",
                                "Old house setting.",
                                List.of("old house"),
                                List.of("house"),
                                List.of(),
                                "PROJECT",
                                List.of("chunk-2")
                        )
                ),
                List.of()
        );

        RuleBasedKnowledgeRetrievalService service = new RuleBasedKnowledgeRetrievalService(
                new FixedRepository(knowledgeBase),
                text -> new KnowledgeEmbedding(List.of(), "", ""),
                new FixedIndexRepository(List.of()),
                new DefaultKnowledgeRetrievalPolicyResolver()
        );

        var result = service.retrieve("project-1", knowledgeBase, new KnowledgeRetrievalQuery(
                KnowledgeRetrievalUseCase.ASSEMBLY,
                "",
                List.of("Alice"),
                List.of("Alice"),
                List.of(KnowledgeCardType.CHARACTER_PROFILE),
                List.of(),
                5,
                2
        ));

        assertEquals(1, result.cards().size());
        assertEquals("card-alice", result.cards().get(0).cardId());
    }

    @Test
    void shouldUseVectorRecallToRaiseSemanticallyMatchedCard() {
        ProjectKnowledgeBase knowledgeBase = new ProjectKnowledgeBase(
                "project-1",
                List.of(
                        new KnowledgeCard(
                                "card-alice",
                                KnowledgeCardType.CHARACTER_PROFILE,
                                "Alice",
                                "Alice is a main character.",
                                List.of("Alice"),
                                List.of("Alice"),
                                List.of(),
                                "PROJECT",
                                List.of("chunk-1")
                        ),
                        new KnowledgeCard(
                                "card-bob",
                                KnowledgeCardType.CHARACTER_PROFILE,
                                "Bob",
                                "Bob is semantically closer to the current query.",
                                List.of("Bob"),
                                List.of("Bob"),
                                List.of(),
                                "PROJECT",
                                List.of("chunk-9")
                        )
                ),
                List.of()
        );

        RuleBasedKnowledgeRetrievalService service = new RuleBasedKnowledgeRetrievalService(
                new FixedRepository(knowledgeBase),
                text -> new KnowledgeEmbedding(List.of(0.1f, 0.2f), "test", "v1"),
                new FixedIndexRepository(List.of(new KnowledgeIndexMatch("card-bob", 0.95D))),
                new DefaultKnowledgeRetrievalPolicyResolver()
        );

        var result = service.retrieve("project-1", knowledgeBase, new KnowledgeRetrievalQuery(
                KnowledgeRetrievalUseCase.ASSEMBLY,
                "",
                List.of("unmatched semantic query"),
                List.of(),
                List.of(KnowledgeCardType.CHARACTER_PROFILE),
                List.of(),
                5,
                2
        ));

        assertEquals(2, result.cards().size());
        assertEquals("card-bob", result.cards().get(0).cardId());
        assertEquals("card-alice", result.cards().get(1).cardId());
    }

    @Test
    void shouldResolveDifferentPoliciesForAssemblyAndSupplementalLookup() {
        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.getAssembly().setDefaultLimit(6);
        properties.getSupplementalLookup().setDefaultLimit(3);
        properties.getAssembly().setVectorRecallMultiplier(3);
        properties.getSupplementalLookup().setVectorRecallMultiplier(4);
        properties.getAssembly().setExactAnchorMatchWeight(28);
        properties.getSupplementalLookup().setExactAnchorMatchWeight(8);
        properties.getAssembly().setPreferredTypeWeight(14);
        properties.getSupplementalLookup().setPreferredTypeWeight(24);

        DefaultKnowledgeRetrievalPolicyResolver resolver = new DefaultKnowledgeRetrievalPolicyResolver(properties);
        KnowledgeRetrievalPolicy assembly = resolver.resolve(KnowledgeRetrievalUseCase.ASSEMBLY);
        KnowledgeRetrievalPolicy supplemental = resolver.resolve(KnowledgeRetrievalUseCase.SUPPLEMENTAL_LOOKUP);

        assertEquals(6, assembly.defaultLimit());
        assertEquals(3, supplemental.defaultLimit());
        assertTrue(supplemental.vectorRecallMultiplier() > assembly.vectorRecallMultiplier());
        assertTrue(assembly.exactAnchorMatchWeight() > supplemental.exactAnchorMatchWeight());
        assertTrue(supplemental.preferredTypeWeight() > assembly.preferredTypeWeight());
    }

    @Test
    void shouldPreferExplicitAnchorPrecisionDuringAssemblyOverPureSemanticMatch() {
        ProjectKnowledgeBase knowledgeBase = new ProjectKnowledgeBase(
                "project-1",
                List.of(
                        new KnowledgeCard(
                                "card-alice",
                                KnowledgeCardType.CHARACTER_PROFILE,
                                "Alice",
                                "Alice is the heroine who appears in this chunk.",
                                List.of("heroine"),
                                List.of("Alice"),
                                List.of(),
                                "PROJECT",
                                List.of("chunk-7")
                        ),
                        new KnowledgeCard(
                                "card-bob",
                                KnowledgeCardType.CHARACTER_PROFILE,
                                "Bob",
                                "Bob is semantically similar to the phrase main heroine.",
                                List.of("main heroine"),
                                List.of("Bob"),
                                List.of(),
                                "PROJECT",
                                List.of("chunk-9")
                        )
                ),
                List.of()
        );

        RuleBasedKnowledgeRetrievalService service = new RuleBasedKnowledgeRetrievalService(
                new FixedRepository(knowledgeBase),
                text -> new KnowledgeEmbedding(List.of(0.1f, 0.2f), "test", "v1"),
                new FixedIndexRepository(List.of(new KnowledgeIndexMatch("card-bob", 0.95D))),
                new DefaultKnowledgeRetrievalPolicyResolver()
        );

        var result = service.retrieve("project-1", knowledgeBase, new KnowledgeRetrievalQuery(
                KnowledgeRetrievalUseCase.ASSEMBLY,
                "",
                List.of("main heroine"),
                List.of("Alice"),
                List.of(KnowledgeCardType.CHARACTER_PROFILE),
                List.of(),
                5,
                2
        ));

        assertEquals(2, result.cards().size());
        assertEquals("card-alice", result.cards().get(0).cardId());
        assertEquals("card-bob", result.cards().get(1).cardId());
    }

    @Test
    void shouldPreferRequestedTypesDuringSupplementalLookupEvenIfAnotherVectorHitIsHigher() {
        ProjectKnowledgeBase knowledgeBase = new ProjectKnowledgeBase(
                "project-1",
                List.of(
                        new KnowledgeCard(
                                "card-character",
                                KnowledgeCardType.CHARACTER_PROFILE,
                                "Louki",
                                "Character profile with high semantic similarity but wrong type for this lookup.",
                                List.of("Louki"),
                                List.of("Louki"),
                                List.of(),
                                "PROJECT",
                                List.of("chunk-1")
                        ),
                        new KnowledgeCard(
                                "card-term",
                                KnowledgeCardType.TERM_EXPLANATION,
                                "dark star",
                                "Explains the imagery and literary meaning of dark star.",
                                List.of("dark star"),
                                List.of("dark star", "imagery"),
                                List.of(),
                                "PROJECT",
                                List.of("chunk-1")
                        )
                ),
                List.of()
        );

        RuleBasedKnowledgeRetrievalService service = new RuleBasedKnowledgeRetrievalService(
                new FixedRepository(knowledgeBase),
                text -> new KnowledgeEmbedding(List.of(0.1f, 0.2f), "test", "v1"),
                new FixedIndexRepository(List.of(
                        new KnowledgeIndexMatch("card-character", 0.99D),
                        new KnowledgeIndexMatch("card-term", 0.60D)
                )),
                new DefaultKnowledgeRetrievalPolicyResolver()
        );

        var result = service.retrieve("project-1", knowledgeBase, new KnowledgeRetrievalQuery(
                KnowledgeRetrievalUseCase.SUPPLEMENTAL_LOOKUP,
                "",
                List.of("symbolic wording in this sentence"),
                List.of("dark star"),
                List.of(KnowledgeCardType.TERM_EXPLANATION),
                List.of(),
                3,
                2
        ));

        assertEquals(2, result.cards().size());
        assertEquals("card-term", result.cards().get(0).cardId());
        assertEquals("card-character", result.cards().get(1).cardId());
    }

    private record FixedRepository(ProjectKnowledgeBase knowledgeBase) implements ProjectKnowledgeBaseRepository {
        @Override
        public Optional<ProjectKnowledgeBase> load(String projectId) {
            return Optional.ofNullable(knowledgeBase);
        }

        @Override
        public void save(ProjectKnowledgeBase knowledgeBase) {
            throw new UnsupportedOperationException();
        }
    }

    private record FixedIndexRepository(List<KnowledgeIndexMatch> matches) implements KnowledgeIndexRepository {
        @Override
        public void replaceProjectIndex(String projectId, List<KnowledgeIndexDocument> documents) {
        }

        @Override
        public List<KnowledgeIndexMatch> searchSimilar(String projectId,
                                                       KnowledgeEmbedding embedding,
                                                       int limit) {
            return matches;
        }
    }
}
