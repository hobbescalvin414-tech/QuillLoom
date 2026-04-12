package io.quillloom.infrastructure.preprocess.intrinsic;

import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.PersonAliasHint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntrinsicEntityCardPlannerTest {

    @Test
    void shouldBuildIntrinsicPersonCardDraftFromChunkSignals() {
        IntrinsicEntityCardPlanner planner = new IntrinsicEntityCardPlanner();
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

        List<IntrinsicEntityCardDraft> drafts = planner.plan(List.of(chunk1, chunk2));

        assertEquals(1, drafts.size());
        assertEquals("Louki", drafts.get(0).canonicalName());
        assertTrue(drafts.get(0).aliasSet().contains("Jacqueline"));
        assertEquals("chunk-1", drafts.get(0).firstSeenChunkId());
        assertTrue(drafts.get(0).evidenceChunks().contains("chunk-1"));
        assertTrue(drafts.get(0).evidenceChunks().contains("chunk-2"));
    }
}
