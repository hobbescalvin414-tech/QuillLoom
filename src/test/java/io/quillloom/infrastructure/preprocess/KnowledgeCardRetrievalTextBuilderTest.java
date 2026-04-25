package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeCardRetrievalTextBuilderTest {

    @Test
    void shouldBuildRetrievalTextFromStableCardFields() {
        KnowledgeCardRetrievalTextBuilder builder = new KnowledgeCardRetrievalTextBuilder();
        KnowledgeCard card = new KnowledgeCard(
                "card-1",
                KnowledgeCardType.CHARACTER_PROFILE,
                "Alice",
                "Alice 是当前段落中的关键人物，需要保持称谓与身份线索一致。",
                List.of("Alice", "heroine"),
                List.of("Alice", "Miss Alice"),
                List.of("source:test"),
                "PROJECT",
                List.of("chunk-1")
        );

        String text = builder.build(card);

        assertTrue(text.contains("知识卡类型：人物卡"));
        assertTrue(text.contains("标题：Alice"));
        assertTrue(text.contains("锚点：Alice、Miss Alice"));
        assertTrue(text.contains("关键词：Alice、heroine"));
        assertTrue(text.contains("正文：Alice 是当前段落中的关键人物"));
    }
}
