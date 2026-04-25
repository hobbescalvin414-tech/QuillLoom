package io.quillloom.application.postdraft.review.port.out;

import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewReadDirection;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationDecisionNote;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PostDraftReviewAgentReader {

    PostDraftContinuationContext loadContinuationContext(String projectId, ReviewFocus focus);

    List<PostDraftChunkRecord> readContinuousChunks(String projectId, String chunkId, ReviewReadDirection direction, int steps);

    List<PostDraftChunkRecord> expandByBlock(String projectId, String chunkId);

    List<TranslationDecisionNote> readDecisionNotes(String projectId, String chunkId);

    Optional<ChunkTransitionNote> readTransitionNote(String projectId, String chunkId);

    List<KnowledgeCard> lookupKnowledgeCards(String projectId, String chunkId, List<String> queryTerms);

    List<PostDraftChunkRecord> readAdjacentChunks(String projectId, String chunkId, int before, int after);

    List<PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword);

    List<String> listChunkIdsByProject(String projectId);

    Optional<PostDraftChunkRecord> loadChunkById(String projectId, String chunkId);

    Map<String, String> readConfirmedTerms(String projectId, List<String> sourceTerms);
}
