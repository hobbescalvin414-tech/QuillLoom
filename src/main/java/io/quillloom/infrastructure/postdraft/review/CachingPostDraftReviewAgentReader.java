package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewReadDirection;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationDecisionNote;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class CachingPostDraftReviewAgentReader implements PostDraftReviewAgentReader {

    private final PostDraftReviewAgentReader delegate;
    private final ConcurrentHashMap<String, PostDraftReviewPackage> reviewPackageCache;
    private final ConcurrentHashMap<String, ProjectKnowledgeBase> knowledgeBaseCache;

    public CachingPostDraftReviewAgentReader(PostDraftReviewAgentReader delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.reviewPackageCache = new ConcurrentHashMap<>();
        this.knowledgeBaseCache = new ConcurrentHashMap<>();
    }

    @Override
    public PostDraftContinuationContext loadContinuationContext(String projectId, ReviewFocus focus) {
        return delegate.loadContinuationContext(projectId, focus);
    }

    @Override
    public List<PostDraftChunkRecord> readContinuousChunks(String projectId, String chunkId, ReviewReadDirection direction, int steps) {
        return delegate.readContinuousChunks(projectId, chunkId, direction, steps);
    }

    @Override
    public List<PostDraftChunkRecord> expandByBlock(String projectId, String chunkId) {
        return delegate.expandByBlock(projectId, chunkId);
    }

    @Override
    public List<TranslationDecisionNote> readDecisionNotes(String projectId, String chunkId) {
        return delegate.readDecisionNotes(projectId, chunkId);
    }

    @Override
    public Optional<ChunkTransitionNote> readTransitionNote(String projectId, String chunkId) {
        return delegate.readTransitionNote(projectId, chunkId);
    }

    @Override
    public List<KnowledgeCard> lookupKnowledgeCards(String projectId, String chunkId, List<String> queryTerms) {
        return delegate.lookupKnowledgeCards(projectId, chunkId, queryTerms);
    }

    @Override
    public List<PostDraftChunkRecord> readAdjacentChunks(String projectId, String chunkId, int before, int after) {
        return delegate.readAdjacentChunks(projectId, chunkId, before, after);
    }

    @Override
    public List<PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword) {
        return delegate.searchChunksByKeyword(projectId, keyword);
    }

    @Override
    public List<String> listChunkIdsByProject(String projectId) {
        return delegate.listChunkIdsByProject(projectId);
    }

    @Override
    public Optional<PostDraftChunkRecord> loadChunkById(String projectId, String chunkId) {
        return delegate.loadChunkById(projectId, chunkId);
    }

    @Override
    public Map<String, String> readConfirmedTerms(String projectId, List<String> sourceTerms) {
        return delegate.readConfirmedTerms(projectId, sourceTerms);
    }

    public PostDraftReviewPackage getCachedReviewPackage(String projectId) {
        return reviewPackageCache.get(projectId);
    }

    public void putCachedReviewPackage(String projectId, PostDraftReviewPackage reviewPackage) {
        if (projectId != null && reviewPackage != null) {
            reviewPackageCache.put(projectId, reviewPackage);
        }
    }

    public ProjectKnowledgeBase getCachedKnowledgeBase(String projectId) {
        return knowledgeBaseCache.get(projectId);
    }

    public void putCachedKnowledgeBase(String projectId, ProjectKnowledgeBase knowledgeBase) {
        if (projectId != null && knowledgeBase != null) {
            knowledgeBaseCache.put(projectId, knowledgeBase);
        }
    }

    public void invalidateCache(String projectId) {
        if (projectId != null) {
            reviewPackageCache.remove(projectId);
            knowledgeBaseCache.remove(projectId);
        }
    }

    public void invalidateAll() {
        reviewPackageCache.clear();
        knowledgeBaseCache.clear();
    }
}
