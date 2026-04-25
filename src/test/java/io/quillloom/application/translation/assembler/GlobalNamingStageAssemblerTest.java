package io.quillloom.application.translation.assembler;

import io.quillloom.domain.knowledge.CandidateTerm;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.PersonAliasHint;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalNamingStageAssemblerTest {

    @Test
    void shouldBuildGlobalGlossaryAndAliasTableFromExistingSignals() {
        GlobalNamingStageAssembler assembler = new GlobalNamingStageAssembler();

        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-1",
                Map.of("Louki", "露姬"),
                List.of(),
                List.of(),
                List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "已有候选", true))
        );
        ProjectKnowledgeBase knowledgeBase = new ProjectKnowledgeBase(
                "project-1",
                List.of(createIntrinsicCharacterCard()),
                List.of(new CandidateTerm("Jacqueline", List.of("雅克琳"), "PERSON", "项目候选术语"))
        );

        var result = assembler.assemble(
                createChunk(),
                projectMemory,
                knowledgeBase,
                List.of(createSelectedCardWithEvidence())
        );

        assertEquals("露姬", result.globalGlossary().hardEntries().get(0).targetTerm());
        assertTrue(result.globalGlossary().softEntries().stream()
                .anyMatch(entry -> entry.sourceTerm().equals("Black Maria") && entry.targetTerm().equals("黑色马车")));
        assertTrue(result.globalGlossary().softEntries().stream()
                .anyMatch(entry -> entry.sourceTerm().equals("Jacqueline") && entry.targetTerm().equals("雅克琳")));
        assertTrue(result.aliasConsistencyTable().clusters().stream()
                .anyMatch(cluster -> cluster.surfaceForms().contains("Louki") && cluster.surfaceForms().contains("Jacqueline")));
    }

    @Test
    void shouldNotPromoteKnowledgeCardTitleOrAliasHintIntoStableGlossaryEntryWithoutTranslationEvidence() {
        GlobalNamingStageAssembler assembler = new GlobalNamingStageAssembler();

        ProjectKnowledgeBase knowledgeBase = new ProjectKnowledgeBase(
                "project-1",
                List.of(createIntrinsicCharacterCard()),
                List.of()
        );

        var result = assembler.assemble(
                createChunk(),
                new ProjectMemorySnapshot("project-1", Map.of(), List.of(), List.of(), List.of()),
                knowledgeBase,
                List.of(new KnowledgeCard(
                        "card-raw",
                        KnowledgeCardType.CHARACTER_PROFILE,
                        "Louki",
                        "只提供人物背景，不提供稳定中文译名。",
                        List.of("Louki"),
                        List.of("Louki"),
                        List.of("chunk-1"),
                        "PROJECT",
                        List.of("chunk-1")
                ))
        );

        assertTrue(result.globalGlossary().hardEntries().isEmpty());
        assertFalse(result.globalGlossary().softEntries().stream()
                .anyMatch(entry -> entry.sourceKind().name().equals("KNOWLEDGE_CARD_DERIVED") && entry.targetTerm().equals("Louki")));
        assertTrue(result.aliasConsistencyTable().clusters().stream()
                .anyMatch(cluster -> cluster.surfaceForms().contains("Louki") && cluster.surfaceForms().contains("Jacqueline")));
    }

    private ChunkAnnotation createChunk() {
        return new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, 32, "Louki, also called Jacqueline, waited for the Black Maria."),
                "摘要",
                List.of("Louki", "Jacqueline", "Black Maria"),
                List.of(),
                List.of(),
                List.of("Black Maria"),
                List.of(new PersonAliasHint(
                        List.of("Louki", "Jacqueline"),
                        "same-person-name-variant",
                        "HIGH",
                        "同段切换称呼"
                ))
        );
    }

    private KnowledgeCard createIntrinsicCharacterCard() {
        return new KnowledgeCard(
                "card-intrinsic",
                KnowledgeCardType.CHARACTER_PROFILE,
                "Louki 人物卡",
                "Louki 是书内关键人物。",
                List.of("Louki", "Jacqueline"),
                List.of("Louki"),
                List.of("chunk-1"),
                "PROJECT",
                List.of("chunk-1"),
                Map.of(
                        "canonicalName", "Louki",
                        "aliasState", "SUSPECTED_ALIAS",
                        "confidence", "HIGH",
                        "surfaceForms", List.of("Louki", "Jacqueline")
                )
        );
    }

    private KnowledgeCard createSelectedCardWithEvidence() {
        return new KnowledgeCard(
                "card-selected",
                KnowledgeCardType.TERM_EXPLANATION,
                "Black Maria 说明卡",
                "Black Maria 在本书中指代一辆旧式马车，当前候选译法可统一为黑色马车。",
                List.of("Black Maria", "黑色马车"),
                List.of("Black Maria"),
                List.of("chunk-1"),
                "PROJECT",
                List.of("chunk-1"),
                Map.of("recommendedTranslation", "黑色马车")
        );
    }
}
