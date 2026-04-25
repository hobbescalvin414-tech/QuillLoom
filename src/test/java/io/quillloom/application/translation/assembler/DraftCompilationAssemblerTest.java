package io.quillloom.application.translation.assembler;

import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.DraftCompilationInput;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftCompilationAssemblerTest {

    @Test
    void shouldCompileChunkDraftsWithoutRewritingThem() {
        ChunkTranslationDraft first = new ChunkTranslationDraft(
                "chunk-1",
                "第一段译文。",
                "保持叙事平稳。",
                List.of(new TranslationDecisionNote("unresolved", "p1", "术语仍待确认。", "后文继续观察。")),
                Map.of("Alice", "爱丽丝"),
                List.of(new TranslationCandidateUpdate("Bob", "鲍勃", "暂按常见译法保留。", true)),
                new ChunkTransitionNote("承接上一段回忆。", "下一段转入街景。", true)
        );

        ChunkTranslationDraft second = new ChunkTranslationDraft(
                "chunk-2",
                "第二段译文。",
                "保留场景切换。",
                List.of(new TranslationDecisionNote("risk", "p2", "语气可能偏硬。", "拼接后复查边界。")),
                Map.of(),
                List.of(),
                new ChunkTransitionNote("延续街景描写。", "下一段进入对话。", false)
        );

        DraftCompilationAssembler assembler = new DraftCompilationAssembler();
        var compilation = assembler.assemble(new DraftCompilationInput("project-1", List.of(first, second)));

        assertEquals("project-1", compilation.projectId());
        assertEquals(2, compilation.chunkDrafts().size());
        assertTrue(compilation.mergedDraft().contains("第一段译文。"));
        assertTrue(compilation.mergedDraft().contains("第二段译文。"));
        assertEquals(2, compilation.carriedDecisionNotes().size());
    }
}