package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.application.postdraft.review.service.PostDraftRetranslationService;
import io.quillloom.application.postdraft.review.service.RetranslationDraftProvider;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftRetranslationServiceTest {

    @Test
    void shouldProduceRevisionDraftForRetranslate() {
        PostDraftRetranslationService service = new PostDraftRetranslationService(
                (session, chunk) -> new RevisionDraft(
                        "new translated text",
                        RevisionMode.RETRANSLATE,
                        List.of("rationale-1"),
                        List.of()
                )
        );

        RevisionDraft draft = service.retranslate(session(), chunk("chunk-1", "source text", "old text"));

        assertEquals(RevisionMode.RETRANSLATE, draft.revisionMode());
        assertEquals("new translated text", draft.formalTranslation());
    }

    @Test
    void shouldFailWhenSourceTextIsBlank() {
        PostDraftRetranslationService service = new PostDraftRetranslationService(
                (session, chunk) -> new RevisionDraft(
                        "new translated text",
                        RevisionMode.RETRANSLATE,
                        List.of(),
                        List.of()
                )
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                service.retranslate(session(), chunk("chunk-2", "   ", "old text"))
        );

        assertEquals("Retranslation requires non-blank sourceText for chunk=chunk-2", exception.getMessage());
    }

    @Test
    void shouldFailWhenDraftModeIsNotRetranslate() {
        PostDraftRetranslationService service = new PostDraftRetranslationService(
                (session, chunk) -> new RevisionDraft(
                        "new translated text",
                        RevisionMode.LIGHT_EDIT,
                        List.of(),
                        List.of()
                )
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                service.retranslate(session(), chunk("chunk-3", "source text", "old text"))
        );

        assertEquals("Retranslation draft must use RETRANSLATE mode", exception.getMessage());
    }

    private static PostDraftReviewSession session() {
        return new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                "operator-note",
                List.of(),
                Set.of(),
                List.of("seed-evidence"),
                ReviewStrategy.RETRANSLATE,
                false,
                ReviewAgentState.REVISING,
                List.of(),
                Set.of(),
                List.of("key-rationale"),
                List.of(),
                List.of()
        );
    }

    private static PostDraftChunkRecord chunk(String chunkId, String sourceText, String translatedText) {
        return new PostDraftChunkRecord(
                chunkId,
                1,
                "block-1",
                sourceText,
                translatedText,
                "commentary",
                List.of(),
                Map.of(),
                List.<TranslationCandidateUpdate>of(),
                null
        );
    }
}
