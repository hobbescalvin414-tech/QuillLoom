package io.quillloom.domain.postdraft;

import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;

import java.util.List;
import java.util.Map;

public record PostDraftChunkRecord(
        String chunkId,
        int sequence,
        String blockId,
        String sourceText,
        String translatedText,
        String revisedTranslatedText,
        String translatorCommentary,
        List<TranslationDecisionNote> decisionNotes,
        Map<String, String> confirmedTermUpdates,
        List<TranslationCandidateUpdate> candidateUpdates,
        ChunkTransitionNote transitionNote
) {

    public PostDraftChunkRecord {
        decisionNotes = decisionNotes == null ? List.of() : List.copyOf(decisionNotes);
        confirmedTermUpdates = confirmedTermUpdates == null ? Map.of() : Map.copyOf(confirmedTermUpdates);
        candidateUpdates = candidateUpdates == null ? List.of() : List.copyOf(candidateUpdates);
    }

    public PostDraftChunkRecord(String chunkId,
                                int sequence,
                                String blockId,
                                String sourceText,
                                String translatedText,
                                String translatorCommentary,
                                List<TranslationDecisionNote> decisionNotes,
                                Map<String, String> confirmedTermUpdates,
                                List<TranslationCandidateUpdate> candidateUpdates,
                                ChunkTransitionNote transitionNote) {
        this(
                chunkId,
                sequence,
                blockId,
                sourceText,
                translatedText,
                null,
                translatorCommentary,
                decisionNotes,
                confirmedTermUpdates,
                candidateUpdates,
                transitionNote
        );
    }

    public String effectiveTranslatedText() {
        if (revisedTranslatedText != null && !revisedTranslatedText.isBlank()) {
            return revisedTranslatedText.trim();
        }
        return translatedText == null ? null : translatedText.trim();
    }
}
