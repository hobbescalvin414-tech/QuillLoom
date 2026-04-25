package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmKnowledgeNeedPlannerTest {

    @Test
    void shouldSelectOnlyStableKnowledgeNeedsForBackgroundQuestion() {
        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor(
                        "chunk-1",
                        1,
                        "block-1",
                        0,
                        120,
                        "Alice visited St. Mary parish."
                ),
                "Alice needs etiquette background before visiting the parish.",
                List.of("Alice", "St. Mary parish"),
                List.of("What are the rules of Victorian church etiquette?"),
                List.of("Religious background may affect tone and address."),
                List.of("church etiquette", "parish")
        );

        LlmKnowledgeNeedPlanner planner = new LlmKnowledgeNeedPlanner(
                new KnowledgeNeedPlanningPromptRenderer(),
                prompt -> """
                        {
                          "needs": [
                            {
                              "shouldSearch": true,
                              "cardType": "CULTURAL_BACKGROUND",
                              "queryText": "Victorian church etiquette rules and forms of address",
                              "anchorNames": ["St. Mary parish"],
                              "keywords": ["Victorian", "church", "etiquette", "address"],
                              "originRefs": ["chunk:chunk-1#backgroundQuestion:1"],
                              "reason": "需要礼仪背景来稳定称呼与叙述语气。",
                              "priority": 1
                            }
                          ]
                        }
                        """,
                new KnowledgeNeedPlanningResultParser()
        );

        List<KnowledgeNeed> needs = planner.plan(chunk);

        assertEquals(1, needs.size());
        assertEquals(KnowledgeCardType.CULTURAL_BACKGROUND, needs.get(0).cardType());
        assertTrue(needs.get(0).queryText().contains("Victorian"));
        assertTrue(needs.get(0).queryText().contains("church"));
        assertTrue(needs.get(0).queryText().contains("etiquette"));
        assertTrue(needs.get(0).queryText().contains("address"));
        assertFalse(needs.get(0).queryText().contains("St. Mary parish"));
        assertFalse(needs.get(0).anchorNames().contains("What are the rules of Victorian church etiquette?"));
        assertEquals(List.of("St. Mary parish"), needs.get(0).anchorNames());
    }

    @Test
    void shouldNormalizeAnalyticalQueryIntoSearchFriendlyTerms() {
        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor(
                        "chunk-2",
                        2,
                        "block-1",
                        121,
                        240,
                        "Dans le cafe"
                ),
                "The title may need literary context.",
                List.of("Dans le cafe", "Patrick Modiano"),
                List.of("Why is this chapter titled Dans le cafe?"),
                List.of("Title wording may carry literary convention."),
                List.of("Dans le cafe")
        );

        LlmKnowledgeNeedPlanner planner = new LlmKnowledgeNeedPlanner(
                new KnowledgeNeedPlanningPromptRenderer(),
                prompt -> """
                        {
                          "needs": [
                            {
                              "shouldSearch": true,
                              "cardType": "CULTURAL_BACKGROUND",
                              "queryText": "French literary use of 'Dans le cafe' as chapter title",
                              "anchorNames": ["Dans le cafe"],
                              "keywords": ["French literature", "chapter title convention"],
                              "originRefs": ["chunk:chunk-2#backgroundQuestion:1"],
                              "reason": "需要标题文化背景",
                              "priority": 1
                            }
                          ]
                        }
                        """,
                new KnowledgeNeedPlanningResultParser()
        );

        List<KnowledgeNeed> needs = planner.plan(chunk);

        assertEquals(1, needs.size());
        assertEquals("Dans le cafe French literary chapter title", needs.get(0).queryText());
        assertFalse(needs.get(0).queryText().contains("'"));
        assertFalse(needs.get(0).queryText().toLowerCase().contains("use of"));
        assertTrue(needs.get(0).queryText().length() <= 64);
    }

    @Test
    void shouldExpandNeedsAcrossBackgroundRiskAndExpressionSignals() {
        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor(
                        "chunk-3",
                        3,
                        "block-1",
                        241,
                        420,
                        "He addressed the parish priest with stiff courtesy and repeated the phrase dark star."
                ),
                "The chunk mixes church etiquette, register risk, and an imagery-heavy phrase.",
                List.of("Alice", "St. Mary parish", "parish priest"),
                List.of("What etiquette governed speaking to a parish priest in Victorian England?"),
                List.of(
                        "The address hierarchy may need a culturally appropriate Chinese rendering.",
                        "dark star may need symbolic translation strategy instead of literal wording."
                ),
                List.of("dark star", "stiff courtesy")
        );

        LlmKnowledgeNeedPlanner planner = new LlmKnowledgeNeedPlanner(
                new KnowledgeNeedPlanningPromptRenderer(),
                prompt -> """
                        {
                          "needs": [
                            {
                              "shouldSearch": true,
                              "needKind": "BACKGROUND_CONTEXT",
                              "signalSource": "backgroundQuestion",
                              "searchIntent": "cultural_norm",
                              "coverageKey": "victorian-parish-priest-etiquette",
                              "cardType": "CULTURAL_BACKGROUND",
                              "queryText": "Victorian parish priest etiquette forms of address",
                              "anchorNames": ["parish priest", "St. Mary parish"],
                              "keywords": ["Victorian", "parish priest", "etiquette", "address"],
                              "originRefs": ["chunk:chunk-3#backgroundQuestion:1"],
                              "reason": "需要礼仪背景。",
                              "priority": 1
                            },
                            {
                              "shouldSearch": true,
                              "needKind": "TRANSLATION_SUPPORT",
                              "signalSource": "translationRisk",
                              "searchIntent": "translation_strategy",
                              "coverageKey": "parish-priest-address-register",
                              "cardType": "TERM_EXPLANATION",
                              "queryText": "parish priest address register translation",
                              "anchorNames": ["parish priest"],
                              "keywords": ["parish priest", "address", "register", "translation"],
                              "originRefs": ["chunk:chunk-3#translationRisk:1"],
                              "reason": "需要称谓翻译策略。",
                              "priority": 2
                            },
                            {
                              "shouldSearch": true,
                              "needKind": "EXPRESSION_CONTEXT",
                              "signalSource": "keyExpression",
                              "searchIntent": "imagery_origin",
                              "coverageKey": "dark-star-symbolism",
                              "cardType": "IMAGERY",
                              "queryText": "dark star symbolism literature",
                              "anchorNames": ["dark star"],
                              "keywords": ["dark star", "symbolism", "literature"],
                              "originRefs": ["chunk:chunk-3#keyExpression:1"],
                              "reason": "需要意象解释。",
                              "priority": 3
                            }
                          ]
                        }
                        """,
                new KnowledgeNeedPlanningResultParser()
        );

        List<KnowledgeNeed> needs = planner.plan(chunk);

        assertEquals(3, needs.size());
        assertEquals(List.of(
                KnowledgeNeedSignalSource.BACKGROUND_QUESTION,
                KnowledgeNeedSignalSource.TRANSLATION_RISK,
                KnowledgeNeedSignalSource.KEY_EXPRESSION
        ), needs.stream().map(KnowledgeNeed::signalSource).toList());
        assertEquals(List.of(
                KnowledgeNeedKind.BACKGROUND_CONTEXT,
                KnowledgeNeedKind.TRANSLATION_SUPPORT,
                KnowledgeNeedKind.EXPRESSION_CONTEXT
        ), needs.stream().map(KnowledgeNeed::needKind).toList());
        assertTrue(needs.stream().anyMatch(need -> need.queryText().contains("translation")));
        assertTrue(needs.stream().anyMatch(need -> need.queryText().contains("symbolism")));
        assertTrue(needs.stream().allMatch(need -> need.coverageKey() != null && !need.coverageKey().isBlank()));
    }

    @Test
    void shouldDedupeEquivalentNeedsAndKeepBudgetForDiverseSignals() {
        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor(
                        "chunk-4",
                        4,
                        "block-1",
                        421,
                        620,
                        "Louki entered the parish in silence under a dark star."
                ),
                "A single chunk contains repeated etiquette and imagery signals.",
                List.of("Louki", "parish", "dark star"),
                List.of("What are the rules of Victorian church etiquette?"),
                List.of(
                        "Need proper address strategy for church interaction.",
                        "Need proper address strategy for church interaction.",
                        "Need symbolic handling for dark star."
                ),
                List.of("dark star", "church etiquette")
        );

        LlmKnowledgeNeedPlanner planner = new LlmKnowledgeNeedPlanner(
                new KnowledgeNeedPlanningPromptRenderer(),
                prompt -> """
                        {
                          "needs": [
                            {
                              "shouldSearch": true,
                              "needKind": "BACKGROUND_CONTEXT",
                              "signalSource": "backgroundQuestion",
                              "searchIntent": "cultural_norm",
                              "coverageKey": "church-etiquette",
                              "cardType": "CULTURAL_BACKGROUND",
                              "queryText": "Victorian church etiquette forms of address",
                              "anchorNames": ["parish"],
                              "keywords": ["Victorian", "church", "etiquette", "address"],
                              "originRefs": ["chunk:chunk-4#backgroundQuestion:1"],
                              "reason": "礼仪背景",
                              "priority": 1
                            },
                            {
                              "shouldSearch": true,
                              "needKind": "TRANSLATION_SUPPORT",
                              "signalSource": "translationRisk",
                              "searchIntent": "translation_strategy",
                              "coverageKey": "church-etiquette",
                              "cardType": "TERM_EXPLANATION",
                              "queryText": "Victorian church etiquette forms of address",
                              "anchorNames": ["parish"],
                              "keywords": ["church", "etiquette", "address", "translation"],
                              "originRefs": ["chunk:chunk-4#translationRisk:1"],
                              "reason": "重复信号",
                              "priority": 2
                            },
                            {
                              "shouldSearch": true,
                              "needKind": "EXPRESSION_CONTEXT",
                              "signalSource": "keyExpression",
                              "searchIntent": "imagery_origin",
                              "coverageKey": "dark-star-symbolism",
                              "cardType": "IMAGERY",
                              "queryText": "dark star symbolism literature",
                              "anchorNames": ["dark star"],
                              "keywords": ["dark star", "symbolism", "literature"],
                              "originRefs": ["chunk:chunk-4#keyExpression:1"],
                              "reason": "意象解释",
                              "priority": 3
                            }
                          ]
                        }
                        """,
                new KnowledgeNeedPlanningResultParser()
        );

        List<KnowledgeNeed> needs = planner.plan(chunk);

        assertEquals(2, needs.size());
        assertEquals(List.of("church-etiquette", "dark-star-symbolism"),
                needs.stream().map(KnowledgeNeed::coverageKey).toList());
        assertEquals(List.of(
                KnowledgeNeedSignalSource.BACKGROUND_QUESTION,
                KnowledgeNeedSignalSource.KEY_EXPRESSION
        ), needs.stream().map(KnowledgeNeed::signalSource).toList());
    }
}
