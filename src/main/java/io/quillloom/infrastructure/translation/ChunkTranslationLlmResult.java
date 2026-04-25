package io.quillloom.infrastructure.translation;

import java.util.List;

public record ChunkTranslationLlmResult(
        String translatedText,
        String translatorCommentary,
        List<ChunkTranslationDecisionNoteResult> decisionNotes,
        List<ConfirmedTermUpdateResult> confirmedTermUpdates,
        List<ChunkTranslationCandidateUpdateResult> candidateUpdates,
        ChunkTranslationTransitionNoteResult transitionNote,
        ChunkTranslationKnowledgeLookupRequestResult knowledgeLookupRequest
) {

    public ChunkTranslationLlmResult(String translatedText,
                                     String translatorCommentary,
                                     List<ChunkTranslationDecisionNoteResult> decisionNotes,
                                     List<ConfirmedTermUpdateResult> confirmedTermUpdates,
                                     List<ChunkTranslationCandidateUpdateResult> candidateUpdates,
                                     ChunkTranslationTransitionNoteResult transitionNote) {
        this(
                translatedText,
                translatorCommentary,
                decisionNotes,
                confirmedTermUpdates,
                candidateUpdates,
                transitionNote,
                null
        );
    }
}