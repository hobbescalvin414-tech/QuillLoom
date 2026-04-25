package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeSearchGateTest {

    @Test
    void shouldKeepDiverseSignalsUnderBudgetBeforeTakingLowerPriorityDuplicates() {
        KnowledgeSearchGateProperties properties = new KnowledgeSearchGateProperties();
        properties.setMaxQueriesPerChunk(3);

        KnowledgeSearchGate gate = new KnowledgeSearchGate(properties);

        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, 200, "Alice greeted the priest under a dark star."),
                "Church etiquette, register, and imagery all matter here.",
                List.of("Alice", "priest", "dark star"),
                List.of("What etiquette governed speaking to a parish priest?"),
                List.of("Address hierarchy may need strategy.", "dark star may require symbolic handling."),
                List.of("dark star", "stiff courtesy")
        );

        List<KnowledgeNeed> allowed = gate.filterNeeds(
                chunk,
                ProjectKnowledgeBase.empty("project-1"),
                List.of(
                        need(KnowledgeNeedKind.BACKGROUND_CONTEXT, KnowledgeNeedSignalSource.BACKGROUND_QUESTION,
                                "church-etiquette", "Victorian parish priest etiquette", 1),
                        need(KnowledgeNeedKind.TRANSLATION_SUPPORT, KnowledgeNeedSignalSource.TRANSLATION_RISK,
                                "address-register", "parish priest address register translation", 2),
                        need(KnowledgeNeedKind.EXPRESSION_CONTEXT, KnowledgeNeedSignalSource.KEY_EXPRESSION,
                                "dark-star-symbolism", "dark star symbolism literature", 3),
                        need(KnowledgeNeedKind.TRANSLATION_SUPPORT, KnowledgeNeedSignalSource.TRANSLATION_RISK,
                                "church-etiquette-duplicate", "church etiquette tone translation", 4)
                )
        );

        assertEquals(3, allowed.size());
        assertEquals(List.of(
                KnowledgeNeedSignalSource.BACKGROUND_QUESTION,
                KnowledgeNeedSignalSource.TRANSLATION_RISK,
                KnowledgeNeedSignalSource.KEY_EXPRESSION
        ), allowed.stream().map(KnowledgeNeed::signalSource).toList());
    }

    @Test
    void shouldSkipCoveredNeedWithoutBlockingOtherSignalChannels() {
        KnowledgeSearchGateProperties properties = new KnowledgeSearchGateProperties();
        properties.setMaxQueriesPerChunk(3);

        KnowledgeSearchGate gate = new KnowledgeSearchGate(properties);

        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, 200, "Alice greeted the priest under a dark star."),
                "Church etiquette, register, and imagery all matter here.",
                List.of("Alice", "priest", "dark star"),
                List.of("What etiquette governed speaking to a parish priest?"),
                List.of("Address hierarchy may need strategy.", "dark star may require symbolic handling."),
                List.of("dark star", "stiff courtesy")
        );

        ProjectKnowledgeBase knowledgeBase = new ProjectKnowledgeBase(
                "project-1",
                List.of(new KnowledgeCard(
                        "kc-1",
                        KnowledgeCardType.CULTURAL_BACKGROUND,
                        "Victorian parish priest etiquette",
                        "Existing etiquette card.",
                        List.of("etiquette"),
                        List.of("priest"),
                        List.of("https://example.com/existing"),
                        "PROJECT",
                        List.of("chunk-0")
                )),
                List.of()
        );

        List<KnowledgeNeed> allowed = gate.filterNeeds(
                chunk,
                knowledgeBase,
                List.of(
                        need(KnowledgeNeedKind.BACKGROUND_CONTEXT, KnowledgeNeedSignalSource.BACKGROUND_QUESTION,
                                "church-etiquette", "Victorian parish priest etiquette", 1),
                        need(KnowledgeNeedKind.TRANSLATION_SUPPORT, KnowledgeNeedSignalSource.TRANSLATION_RISK,
                                "address-register", "parish priest address register translation", 2),
                        need(KnowledgeNeedKind.EXPRESSION_CONTEXT, KnowledgeNeedSignalSource.KEY_EXPRESSION,
                                "dark-star-symbolism", "dark star symbolism literature", 3)
                )
        );

        assertEquals(2, allowed.size());
        assertEquals(List.of(
                KnowledgeNeedSignalSource.TRANSLATION_RISK,
                KnowledgeNeedSignalSource.KEY_EXPRESSION
        ), allowed.stream().map(KnowledgeNeed::signalSource).toList());
    }

    private KnowledgeNeed need(KnowledgeNeedKind needKind,
                               KnowledgeNeedSignalSource signalSource,
                               String coverageKey,
                               String queryText,
                               int priority) {
        return new KnowledgeNeed(
                KnowledgeCardType.CULTURAL_BACKGROUND,
                queryText,
                List.of("priest"),
                List.of("priest"),
                List.of("chunk:chunk-1#planner"),
                "test",
                priority,
                needKind,
                signalSource,
                coverageKey,
                "test_intent"
        );
    }
}
