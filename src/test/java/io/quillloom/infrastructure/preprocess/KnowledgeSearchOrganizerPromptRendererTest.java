package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchOrganizerPromptRendererTest {

    @Test
    void shouldRenderChunkAndEvidenceIntoPrompt() {
        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, 0, 120, "Alice met Bob in the old church."),
                "Characters meet in a church.",
                List.of("Alice", "old church"),
                List.of("What does this church mean in local religious culture?"),
                List.of("religious context may affect tone"),
                List.of("old church bell")
        );
        KnowledgeNeed need = new KnowledgeNeed(
                KnowledgeCardType.SETTING_ENTRY,
                "old church",
                List.of("old church"),
                List.of("church"),
                List.of("query:old church"),
                "需要教堂背景",
                1
        );
        List<KnowledgeSearchHit> hits = List.of(
                new KnowledgeSearchHit("Victorian church etiquette", "Church etiquette affects forms of address.", "https://example.com/church", "google", List.of())
        );

        String prompt = new KnowledgeSearchOrganizerPromptRenderer().render(chunk, need, hits);

        assertTrue(prompt.contains("old church"));
        assertTrue(prompt.contains("Characters meet in a church."));
        assertTrue(prompt.contains("Victorian church etiquette"));
        assertTrue(prompt.contains("shouldCreateCard"));
        assertTrue(prompt.contains("usedEvidenceIndexes"));
        assertTrue(prompt.contains("originRefs"));
    }
}
