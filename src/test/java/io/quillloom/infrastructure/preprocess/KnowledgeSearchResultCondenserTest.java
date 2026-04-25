package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchResultCondenserTest {

    @Test
    void shouldCondenseCulturalBackgroundHitsIntoTypedContent() {
        KnowledgeNeed need = new KnowledgeNeed(
                KnowledgeCardType.CULTURAL_BACKGROUND,
                "What does this church mean in local religious culture?",
                List.of("old church", "Alice"),
                List.of("church", "religious", "culture"),
                List.of("chunk:chunk-1#backgroundQuestion:1"),
                "需要宗教背景",
                1
        );

        OrganizedKnowledgeEvidence result = new KnowledgeSearchResultCondenser().condense(need, List.of(
                new KnowledgeSearchHit(
                        "Victorian church etiquette",
                        "Church etiquette affects forms of address and narrative distance.",
                        "https://example.com/church-1",
                        "tavily",
                        List.of("etiquette", "address")
                ),
                new KnowledgeSearchHit(
                        "Religious symbolism in parish life",
                        "Parish symbols can signal class position and communal duty.",
                        "https://example.com/church-2",
                        "tavily",
                        List.of("symbolism", "parish")
                )
        ));

        assertNotNull(result);
        assertEquals(KnowledgeCardType.CULTURAL_BACKGROUND, result.cardType());
        assertEquals("Victorian church etiquette", result.title());
        assertTrue(result.content().contains("文化背景：围绕“What does this church mean in local religious culture?”整理出以下背景信息。"));
        assertTrue(result.content().contains("当前可用背景：Church etiquette affects forms of address and narrative distance."));
        assertTrue(result.content().contains("翻译关注点：优先服务当前 chunk 的背景理解"));
        assertTrue(result.content().contains("证据摘录："));
        assertTrue(result.evidenceUrls().contains("https://example.com/church-1"));
    }

    @Test
    void shouldCondenseCharacterHitsIntoCharacterTemplate() {
        KnowledgeNeed need = new KnowledgeNeed(
                KnowledgeCardType.CHARACTER_PROFILE,
                "Alice",
                List.of("Alice"),
                List.of("Alice"),
                List.of("chunk:chunk-1#entity:1"),
                "需要人物背景",
                1
        );

        OrganizedKnowledgeEvidence result = new KnowledgeSearchResultCondenser().condense(need, List.of(
                new KnowledgeSearchHit(
                        "Alice profile",
                        "Alice is portrayed as restrained but socially observant.",
                        "https://example.com/alice",
                        "tavily",
                        List.of("Alice", "restrained")
                )
        ));

        assertNotNull(result);
        assertTrue(result.content().contains("人物线索：围绕“Alice”整理出以下人物相关信息。"));
        assertTrue(result.content().contains("翻译关注点：优先保持人物称谓、身份线索和关系提示一致。"));
    }

    @Test
    void shouldCondenseTermHitsIntoTermTemplate() {
        KnowledgeNeed need = new KnowledgeNeed(
                KnowledgeCardType.TERM_EXPLANATION,
                "Harbor Master",
                List.of("Harbor Master"),
                List.of("Harbor", "Master"),
                List.of("chunk:chunk-1#entity:2"),
                "需要术语解释",
                1
        );

        OrganizedKnowledgeEvidence result = new KnowledgeSearchResultCondenser().condense(need, List.of(
                new KnowledgeSearchHit(
                        "Harbor Master role",
                        "Harbor Master refers to the official overseeing port operations.",
                        "https://example.com/harbor-master",
                        "tavily",
                        List.of("port", "official")
                )
        ));

        assertNotNull(result);
        assertTrue(result.content().contains("术语说明：围绕“Harbor Master”整理出以下解释。"));
        assertTrue(result.content().contains("翻译关注点：优先保持术语含义、称谓层级和上下文使用方式稳定。"));
    }
}
