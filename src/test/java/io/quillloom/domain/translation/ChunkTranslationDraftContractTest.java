package io.quillloom.domain.translation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkTranslationDraftContractTest {

    @Test
    void shouldCarryStructuredDraftFieldsForAgentD() {
        ChunkTranslationDraft draft = new ChunkTranslationDraft(
                "chunk-1",
                "这是当前 chunk 的中文初稿。",
                "本段先保留叙事节奏，不提前定死可疑译名。",
                List.of(new TranslationDecisionNote(
                        "unresolved",
                        "paragraph-2",
                        "Bob 的译名仍需结合后文确认。",
                        "在后续 chunk 中继续观察是否有正式称谓。"
                )),
                Map.of("Alice", "爱丽丝"),
                List.of(new TranslationCandidateUpdate(
                        "Bob",
                        "鲍勃",
                        "当前按常见译法暂存。",
                        true
                )),
                new ChunkTransitionNote(
                        "与上一段保持第一人称回忆语气一致。",
                        "下一段进入巴黎街景描写，建议保留转场缓冲。",
                        true
                )
        );

        assertEquals("这是当前 chunk 的中文初稿。", draft.translatedText());
        assertEquals("爱丽丝", draft.confirmedTermUpdates().get("Alice"));
        assertEquals(1, draft.decisionNotes().size());
        assertEquals(1, draft.candidateUpdates().size());
        assertTrue(draft.transitionNote().boundaryAdjustmentSuggested());
    }
}