package io.quillloom.application.postdraft.review;

import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;

import java.util.List;
import java.util.Map;

final class ReviewAgentFixtures {

    private ReviewAgentFixtures() {
    }

    static PostDraftChunkRecord chunkWithDecisionNote(String chunkId) {
        return chunkWithDecisionNote(chunkId, "pending");
    }

    static PostDraftChunkRecord chunkWithDecisionNote(String chunkId, String issue) {
        return baseChunk(
                chunkId,
                "译文",
                List.of(new TranslationDecisionNote("decision", "src-1", issue, "human review")),
                null
        );
    }

    static PostDraftChunkRecord chunkWithTransitionNote(String chunkId) {
        return baseChunk(
                chunkId,
                "译文",
                List.of(),
                new ChunkTransitionNote("before", "after", true)
        );
    }

    static PostDraftChunkRecord chunkWithTranslation(String chunkId, String translatedText) {
        return baseChunk(
                chunkId,
                translatedText,
                List.of(),
                null
        );
    }

    static PostDraftChunkRecord chunkWithTermUpdate(String chunkId,
                                                    String sourceText,
                                                    String translatedText,
                                                    Map<String, String> confirmedTermUpdates) {
        return new PostDraftChunkRecord(
                chunkId,
                1,
                "block-1",
                sourceText,
                translatedText,
                null,
                "commentary",
                List.of(),
                confirmedTermUpdates,
                List.<TranslationCandidateUpdate>of(),
                null
        );
    }

    private static PostDraftChunkRecord baseChunk(String chunkId,
                                                  String translatedText,
                                                  List<TranslationDecisionNote> decisionNotes,
                                                  ChunkTransitionNote transitionNote) {
        return new PostDraftChunkRecord(
                chunkId,
                1,
                "block-1",
                "source text",
                translatedText,
                "commentary",
                decisionNotes,
                Map.of(),
                List.<TranslationCandidateUpdate>of(),
                transitionNote
        );
    }
}
