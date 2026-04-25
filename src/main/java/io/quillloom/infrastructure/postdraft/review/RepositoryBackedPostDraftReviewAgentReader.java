package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler;
import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewReadDirection;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import io.quillloom.application.translation.model.KnowledgeRetrievalResult;
import io.quillloom.application.translation.model.KnowledgeRetrievalUseCase;
import io.quillloom.application.translation.port.out.KnowledgeRetrievalService;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationDecisionNote;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RepositoryBackedPostDraftReviewAgentReader implements PostDraftReviewAgentReader {

    private final PostDraftReviewPackageRepository reviewPackageRepository;
    private final ProjectKnowledgeBaseRepository knowledgeBaseRepository;
    private final PostDraftContinuationContextAssembler continuationContextAssembler;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final ConcurrentHashMap<String, PostDraftReviewPackage> reviewPackageCache;
    private final ConcurrentHashMap<String, ProjectKnowledgeBase> knowledgeBaseCache;

    public RepositoryBackedPostDraftReviewAgentReader(PostDraftReviewPackageRepository reviewPackageRepository,
                                                      ProjectKnowledgeBaseRepository knowledgeBaseRepository,
                                                      PostDraftContinuationContextAssembler continuationContextAssembler,
                                                      KnowledgeRetrievalService knowledgeRetrievalService) {
        this.reviewPackageRepository = Objects.requireNonNull(reviewPackageRepository, "reviewPackageRepository");
        this.knowledgeBaseRepository = Objects.requireNonNull(knowledgeBaseRepository, "knowledgeBaseRepository");
        this.continuationContextAssembler = Objects.requireNonNull(continuationContextAssembler, "continuationContextAssembler");
        this.knowledgeRetrievalService = Objects.requireNonNull(knowledgeRetrievalService, "knowledgeRetrievalService");
        this.reviewPackageCache = new ConcurrentHashMap<>();
        this.knowledgeBaseCache = new ConcurrentHashMap<>();
    }

    @Override
    public PostDraftContinuationContext loadContinuationContext(String projectId, ReviewFocus focus) {
        String normalizedProjectId = requireText(projectId, "projectId");
        ReviewFocus effectiveFocus = Objects.requireNonNull(focus, "focus");
        PostDraftReviewPackage reviewPackage = loadReviewPackage(normalizedProjectId);
        ensureChunkExists(reviewPackage.chunks(), effectiveFocus.chunkId());
        ProjectKnowledgeBase knowledgeBase = loadKnowledgeBase(normalizedProjectId);
        return continuationContextAssembler.assemble(reviewPackage, knowledgeBase);
    }

    @Override
    public List<PostDraftChunkRecord> readContinuousChunks(String projectId,
                                                           String chunkId,
                                                           ReviewReadDirection direction,
                                                           int steps) {
        String normalizedProjectId = requireText(projectId, "projectId");
        String normalizedChunkId = requireText(chunkId, "chunkId");
        ReviewReadDirection effectiveDirection = Objects.requireNonNull(direction, "direction");
        if (steps < 0) {
            throw new IllegalArgumentException("steps must not be negative");
        }
        if (steps == 0) {
            return List.of();
        }
        PostDraftReviewPackage reviewPackage = loadReviewPackage(normalizedProjectId);
        List<PostDraftChunkRecord> orderedChunks = sortChunks(reviewPackage.chunks());
        int focusIndex = findChunkIndex(orderedChunks, normalizedChunkId);
        if (effectiveDirection == ReviewReadDirection.PREVIOUS) {
            int fromIndex = Math.max(0, focusIndex - steps);
            return List.copyOf(orderedChunks.subList(fromIndex, focusIndex));
        }
        int toIndex = Math.min(orderedChunks.size(), focusIndex + steps + 1);
        return List.copyOf(orderedChunks.subList(focusIndex + 1, toIndex));
    }

    @Override
    public List<PostDraftChunkRecord> expandByBlock(String projectId, String chunkId) {
        String normalizedProjectId = requireText(projectId, "projectId");
        String normalizedChunkId = requireText(chunkId, "chunkId");
        PostDraftReviewPackage reviewPackage = loadReviewPackage(normalizedProjectId);
        PostDraftChunkRecord focusChunk = findChunk(reviewPackage.chunks(), normalizedChunkId);
        return reviewPackage.chunks().stream()
                .filter(chunk -> Objects.equals(focusChunk.blockId(), chunk.blockId()))
                .sorted(Comparator.comparingInt(PostDraftChunkRecord::sequence).thenComparing(PostDraftChunkRecord::chunkId))
                .toList();
    }

    @Override
    public List<TranslationDecisionNote> readDecisionNotes(String projectId, String chunkId) {
        String normalizedProjectId = requireText(projectId, "projectId");
        String normalizedChunkId = requireText(chunkId, "chunkId");
        PostDraftReviewPackage reviewPackage = loadReviewPackage(normalizedProjectId);
        PostDraftChunkRecord chunk = findChunk(reviewPackage.chunks(), normalizedChunkId);
        return List.copyOf(chunk.decisionNotes());
    }

    @Override
    public Optional<ChunkTransitionNote> readTransitionNote(String projectId, String chunkId) {
        String normalizedProjectId = requireText(projectId, "projectId");
        String normalizedChunkId = requireText(chunkId, "chunkId");
        PostDraftReviewPackage reviewPackage = loadReviewPackage(normalizedProjectId);
        PostDraftChunkRecord chunk = findChunk(reviewPackage.chunks(), normalizedChunkId);
        return Optional.ofNullable(chunk.transitionNote());
    }

    @Override
    public List<KnowledgeCard> lookupKnowledgeCards(String projectId, String chunkId, List<String> queryTerms) {
        String normalizedProjectId = requireText(projectId, "projectId");
        String normalizedChunkId = requireText(chunkId, "chunkId");
        List<String> safeQueryTerms = queryTerms == null ? List.of() : queryTerms.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        if (!safeQueryTerms.isEmpty()) {
            KnowledgeRetrievalQuery query = new KnowledgeRetrievalQuery(
                    KnowledgeRetrievalUseCase.REVIEW_AGENT_LOOKUP,
                    normalizedChunkId,
                    safeQueryTerms,
                    List.of(),
                    List.of(),
                    List.of(),
                    5,
                    0
            );
            KnowledgeRetrievalResult result = knowledgeRetrievalService.retrieve(normalizedProjectId, query);
            return result.cards();
        }
        ProjectKnowledgeBase knowledgeBase = loadKnowledgeBase(normalizedProjectId);
        return knowledgeBase.cards().stream()
                .filter(card -> card.applicableChunkIds().contains(normalizedChunkId))
                .toList();
    }

    @Override
    public List<PostDraftChunkRecord> readAdjacentChunks(String projectId, String chunkId, int before, int after) {
        String normalizedProjectId = requireText(projectId, "projectId");
        String normalizedChunkId = requireText(chunkId, "chunkId");
        if (before < 0 || after < 0) {
            throw new IllegalArgumentException("before and after must not be negative");
        }
        PostDraftReviewPackage reviewPackage = loadReviewPackage(normalizedProjectId);
        return sliceWindow(reviewPackage.chunks(), normalizedChunkId, before, after);
    }

    @Override
    public List<PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword) {
        String normalizedProjectId = requireText(projectId, "projectId");
        String normalizedKeyword = requireText(keyword, "keyword").toLowerCase(Locale.ROOT);
        PostDraftReviewPackage reviewPackage = loadReviewPackage(normalizedProjectId);
        return reviewPackage.chunks().stream()
                .filter(chunk -> containsKeyword(chunk, normalizedKeyword))
                .toList();
    }

    @Override
    public List<String> listChunkIdsByProject(String projectId) {
        String normalizedProjectId = requireText(projectId, "projectId");
        PostDraftReviewPackage reviewPackage = loadReviewPackage(normalizedProjectId);
        return sortChunks(reviewPackage.chunks()).stream()
                .map(PostDraftChunkRecord::chunkId)
                .toList();
    }

    @Override
    public Optional<PostDraftChunkRecord> loadChunkById(String projectId, String chunkId) {
        String normalizedProjectId = requireText(projectId, "projectId");
        String normalizedChunkId = requireText(chunkId, "chunkId");
        PostDraftReviewPackage reviewPackage = loadReviewPackage(normalizedProjectId);
        return reviewPackage.chunks().stream()
                .filter(chunk -> normalizedChunkId.equals(chunk.chunkId()))
                .findFirst();
    }

    @Override
    public Map<String, String> readConfirmedTerms(String projectId, List<String> sourceTerms) {
        String normalizedProjectId = requireText(projectId, "projectId");
        List<String> normalizedSourceTerms = sourceTerms == null
                ? List.of()
                : sourceTerms.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (normalizedSourceTerms.isEmpty()) {
            throw new IllegalArgumentException("sourceTerms must not be empty");
        }
        PostDraftReviewPackage reviewPackage = loadReviewPackage(normalizedProjectId);
        LinkedHashMap<String, String> matches = new LinkedHashMap<>();
        Map<String, String> confirmedTerms = reviewPackage.termState().effectiveConfirmedTerms();
        for (String sourceTerm : normalizedSourceTerms) {
            String targetTerm = confirmedTerms.get(sourceTerm);
            if (targetTerm != null && !targetTerm.isBlank()) {
                matches.put(sourceTerm, targetTerm);
            }
        }
        return Map.copyOf(matches);
    }

    private PostDraftReviewPackage loadReviewPackage(String projectId) {
        return reviewPackageCache.computeIfAbsent(projectId, id ->
                reviewPackageRepository.load(id)
                        .orElseThrow(() -> new IllegalStateException("Post-draft review package not found for projectId=" + id))
        );
    }

    private ProjectKnowledgeBase loadKnowledgeBase(String projectId) {
        return knowledgeBaseCache.computeIfAbsent(projectId, id ->
                knowledgeBaseRepository.load(id)
                        .orElseThrow(() -> new IllegalStateException("Project knowledge base not found for projectId=" + id))
        );
    }

    public void invalidateCache(String projectId) {
        if (projectId != null) {
            reviewPackageCache.remove(projectId);
            knowledgeBaseCache.remove(projectId);
        }
    }

    private void ensureChunkExists(List<PostDraftChunkRecord> chunks, String chunkId) {
        if (chunks.stream().noneMatch(chunk -> chunkId.equals(chunk.chunkId()))) {
            throw new IllegalStateException("Chunk not found in post-draft review package: " + chunkId);
        }
    }

    private PostDraftChunkRecord findChunk(List<PostDraftChunkRecord> chunks, String chunkId) {
        return chunks.stream()
                .filter(chunk -> chunkId.equals(chunk.chunkId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Chunk not found in post-draft review package: " + chunkId));
    }

    private int findChunkIndex(List<PostDraftChunkRecord> orderedChunks, String chunkId) {
        for (int index = 0; index < orderedChunks.size(); index++) {
            if (chunkId.equals(orderedChunks.get(index).chunkId())) {
                return index;
            }
        }
        throw new IllegalStateException("Chunk not found in post-draft review package: " + chunkId);
    }

    private List<PostDraftChunkRecord> sortChunks(List<PostDraftChunkRecord> chunks) {
        return chunks.stream()
                .sorted(Comparator.comparingInt(PostDraftChunkRecord::sequence).thenComparing(PostDraftChunkRecord::chunkId))
                .toList();
    }

    private List<PostDraftChunkRecord> sliceWindow(List<PostDraftChunkRecord> chunks, String chunkId, int before, int after) {
        List<PostDraftChunkRecord> orderedChunks = sortChunks(chunks);
        int focusIndex = findChunkIndex(orderedChunks, chunkId);
        int fromIndex = Math.max(0, focusIndex - before);
        int toIndex = Math.min(orderedChunks.size(), focusIndex + after + 1);
        return List.copyOf(orderedChunks.subList(fromIndex, toIndex));
    }

    private boolean containsKeyword(PostDraftChunkRecord chunk, String normalizedKeyword) {
        return contains(chunk.chunkId(), normalizedKeyword)
                || contains(chunk.blockId(), normalizedKeyword)
                || contains(chunk.sourceText(), normalizedKeyword)
                || contains(chunk.effectiveTranslatedText(), normalizedKeyword)
                || contains(chunk.translatorCommentary(), normalizedKeyword);
    }

    private boolean contains(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
