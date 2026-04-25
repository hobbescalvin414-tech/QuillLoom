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
import io.quillloom.domain.knowledge.CandidateTerm;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.postdraft.PostDraftBlockIndex;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationDecisionNote;
import io.quillloom.infrastructure.postdraft.InMemoryPostDraftReviewPackageRepository;
import io.quillloom.infrastructure.preprocess.InMemoryProjectKnowledgeBaseRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryBackedPostDraftReviewAgentReaderTest {

    @Test
    void shouldLoadMinimalContinuationContextFromFormalAssets() {
        InMemoryProjectKnowledgeBaseRepository knowledgeBaseRepository = new InMemoryProjectKnowledgeBaseRepository();
        InMemoryPostDraftReviewPackageRepository reviewPackageRepository = new InMemoryPostDraftReviewPackageRepository();
        PostDraftContinuationContextAssembler assembler = new PostDraftContinuationContextAssembler();
        PostDraftReviewAgentReader reader = new RepositoryBackedPostDraftReviewAgentReader(
                reviewPackageRepository,
                knowledgeBaseRepository,
                assembler,
                new StubKnowledgeRetrievalService()
        );

        reviewPackageRepository.save(reviewPackage("project-1"));
        knowledgeBaseRepository.save(knowledgeBase("project-1"));

        PostDraftContinuationContext context = reader.loadContinuationContext("project-1", ReviewFocus.forChunk("chunk-2"));

        assertEquals("project-1", context.projectId());
        assertEquals(4, context.chunks().size());
        assertEquals("chunk-2", context.chunks().get(1).chunkId());
        assertEquals("block-2", context.blockIndexes().get(1).blockId());
        assertEquals("project-1", context.knowledgeBase().projectId());
        assertEquals(1, context.knowledgeBase().cards().size());
    }

    @Test
    void shouldReadAdjacentChunkWindowAroundFocus() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        List<PostDraftChunkRecord> chunks = reader.readAdjacentChunks("project-1", "chunk-2", 1, 1);

        assertEquals(List.of("chunk-1", "chunk-2", "chunk-3"), chunks.stream().map(PostDraftChunkRecord::chunkId).toList());
    }

    @Test
    void shouldListChunkIdsByProjectSortedBySequence() {
        PostDraftReviewAgentReader reader = readerForCustomChunks(
                "project-1",
                List.of(
                        chunk("chunk-2", 2, "block-2", "source-2", "translated-2", "commentary-2", List.of(), "after-2"),
                        chunk("chunk-1", 1, "block-1", "source-1", "translated-1", "commentary-1", List.of(), "after-1"),
                        chunk("chunk-3", 3, "block-3", "source-3", "translated-3", "commentary-3", List.of(), "after-3")
                )
        );

        List<String> chunkIds = reader.listChunkIdsByProject("project-1");

        assertEquals(List.of("chunk-1", "chunk-2", "chunk-3"), chunkIds);
    }

    @Test
    void shouldLoadChunkByIdFromProjectPackage() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        PostDraftChunkRecord chunk = reader.loadChunkById("project-1", "chunk-3").orElseThrow();

        assertEquals("chunk-3", chunk.chunkId());
        assertTrue(reader.loadChunkById("project-1", "missing-chunk").isEmpty());
    }

    @Test
    void shouldRejectBlankProjectIdWhenListingChunkIds() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reader.listChunkIdsByProject("  ")
        );

        assertEquals("projectId must not be blank", exception.getMessage());
    }

    @Test
    void shouldRejectBlankChunkIdWhenLoadingChunkById() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reader.loadChunkById("project-1", " ")
        );

        assertEquals("chunkId must not be blank", exception.getMessage());
    }

    @Test
    void shouldFailListingChunkIdsWhenProjectPackageMissing() {
        PostDraftReviewAgentReader reader = new RepositoryBackedPostDraftReviewAgentReader(
                new InMemoryPostDraftReviewPackageRepository(),
                new InMemoryProjectKnowledgeBaseRepository(),
                new PostDraftContinuationContextAssembler(),
                new StubKnowledgeRetrievalService()
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> reader.listChunkIdsByProject("missing-project")
        );

        assertTrue(exception.getMessage().contains("Post-draft review package not found"));
    }

    @Test
    void shouldClipAdjacentChunkWindowAtPackageBoundaries() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        List<PostDraftChunkRecord> chunks = reader.readAdjacentChunks("project-1", "chunk-1", 2, 1);

        assertEquals(List.of("chunk-1", "chunk-2"), chunks.stream().map(PostDraftChunkRecord::chunkId).toList());
    }

    @Test
    void shouldSearchChunksByKeywordAcrossPackageTextFields() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        List<PostDraftChunkRecord> chunks = reader.searchChunksByKeyword("project-1", "festival");

        assertEquals(List.of("chunk-3"), chunks.stream().map(PostDraftChunkRecord::chunkId).toList());
    }

    @Test
    void shouldReadContinuousChunksBeforeAndAfterFocusAcrossMultipleSteps() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        List<PostDraftChunkRecord> previousChunks = reader.readContinuousChunks(
                "project-1",
                "chunk-4",
                ReviewReadDirection.PREVIOUS,
                2
        );
        List<PostDraftChunkRecord> nextChunks = reader.readContinuousChunks(
                "project-1",
                "chunk-1",
                ReviewReadDirection.NEXT,
                2
        );

        assertEquals(List.of("chunk-2", "chunk-3"), previousChunks.stream().map(PostDraftChunkRecord::chunkId).toList());
        assertEquals(List.of("chunk-2", "chunk-3"), nextChunks.stream().map(PostDraftChunkRecord::chunkId).toList());
    }

    @Test
    void shouldExpandChunksByBlockFromFormalPackage() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        List<PostDraftChunkRecord> chunks = reader.expandByBlock("project-1", "chunk-2");

        assertEquals(List.of("chunk-2", "chunk-3"), chunks.stream().map(PostDraftChunkRecord::chunkId).toList());
    }

    @Test
    void shouldReadDecisionAndTransitionNotesFromFormalAssets() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        List<TranslationDecisionNote> notes = reader.readDecisionNotes("project-1", "chunk-3");
        ChunkTransitionNote transitionNote = reader.readTransitionNote("project-1", "chunk-3").orElseThrow();

        assertEquals(1, notes.size());
        assertEquals("continuity", notes.get(0).type());
        assertEquals("bridge to ending", transitionNote.nextChunkConnection());
    }

    @Test
    void shouldLookupKnowledgeCardsByApplicableChunkId() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        List<KnowledgeCard> cards = reader.lookupKnowledgeCards("project-1", "chunk-3", List.of());

        assertEquals(1, cards.size());
        assertEquals("card-1", cards.get(0).cardId());
    }

    @Test
    void shouldLookupKnowledgeCardsByVectorSearchWhenQueryTermsProvided() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        List<KnowledgeCard> cards = reader.lookupKnowledgeCards("project-1", "chunk-3", List.of("festival", "customs"));

        assertEquals(1, cards.size());
        assertEquals("card-1", cards.get(0).cardId());
    }

    @Test
    void shouldReturnEmptyKnowledgeCardsWhenNoChunkMappingExists() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        List<KnowledgeCard> cards = reader.lookupKnowledgeCards("project-1", "chunk-4", List.of());

        assertTrue(cards.isEmpty());
    }

    @Test
    void shouldReadConfirmedTermsOnlyForRequestedSourceTerms() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        Map<String, String> confirmedTerms = reader.readConfirmedTerms(
                "project-1",
                List.of("Louki", "Unknown", "Harbor Master")
        );

        assertEquals(Map.of(
                "Louki", "露姬",
                "Harbor Master", "港务长"
        ), confirmedTerms);
    }

    @Test
    void shouldRejectEmptySourceTermsWhenReadingConfirmedTerms() {
        PostDraftReviewAgentReader reader = readerForProject("project-1");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reader.readConfirmedTerms("project-1", List.of())
        );

        assertEquals("sourceTerms must not be empty", exception.getMessage());
    }

    @Test
    void shouldRejectMissingFormalAssets() {
        PostDraftReviewAgentReader reader = new RepositoryBackedPostDraftReviewAgentReader(
                new InMemoryPostDraftReviewPackageRepository(),
                new InMemoryProjectKnowledgeBaseRepository(),
                new PostDraftContinuationContextAssembler(),
                new StubKnowledgeRetrievalService()
        );

        assertThrows(IllegalStateException.class,
                () -> reader.loadContinuationContext("missing-project", ReviewFocus.forChunk("chunk-1")));
    }

    private static PostDraftReviewAgentReader readerForProject(String projectId) {
        return readerForCustomChunks(projectId, reviewPackage(projectId).chunks());
    }

    private static PostDraftReviewAgentReader readerForCustomChunks(String projectId, List<PostDraftChunkRecord> chunks) {
        InMemoryProjectKnowledgeBaseRepository knowledgeBaseRepository = new InMemoryProjectKnowledgeBaseRepository();
        InMemoryPostDraftReviewPackageRepository reviewPackageRepository = new InMemoryPostDraftReviewPackageRepository();
        PostDraftContinuationContextAssembler assembler = new PostDraftContinuationContextAssembler();
        PostDraftReviewAgentReader reader = new RepositoryBackedPostDraftReviewAgentReader(
                reviewPackageRepository,
                knowledgeBaseRepository,
                assembler,
                new StubKnowledgeRetrievalService()
        );
        reviewPackageRepository.save(reviewPackage(projectId, chunks));
        knowledgeBaseRepository.save(knowledgeBase(projectId));
        return reader;
    }

    private static PostDraftReviewPackage reviewPackage(String projectId) {
        return reviewPackage(projectId, List.of(
                chunk("chunk-1", 1, "block-1", "the chapel bell rang", "translated one", "quiet evening", List.of(), "after-one"),
                chunk("chunk-2", 2, "block-2", "the priest answered", "translated two", "careful phrasing", List.of(), "after-two"),
                chunk(
                        "chunk-3",
                        3,
                        "block-2",
                        "festival lights filled the square",
                        "translated three",
                        "festival scene",
                        List.of(new TranslationDecisionNote("continuity", "anchor-1", "bridge missing", "check ending tone")),
                        "bridge to ending"
                ),
                chunk("chunk-4", 4, "block-4", "the town settled into silence", "translated four", "closing note", List.of(), "after-four")
        ));
    }

    private static PostDraftReviewPackage reviewPackage(String projectId, List<PostDraftChunkRecord> chunks) {
        List<String> blockIds = chunks.stream()
                .map(PostDraftChunkRecord::blockId)
                .distinct()
                .toList();
        List<PostDraftBlockIndex> blockIndexes = blockIds.stream()
                .map(blockId -> new PostDraftBlockIndex(
                        blockId,
                        "block-" + blockId,
                        chunks.stream()
                                .filter(chunk -> blockId.equals(chunk.blockId()))
                                .map(PostDraftChunkRecord::chunkId)
                                .toList()
                ))
                .toList();

        return new PostDraftReviewPackage(
                projectId,
                "v1",
                "en",
                "zh",
                "digest-1",
                Instant.parse("2026-04-15T00:00:00Z"),
                chunks,
                blockIndexes,
                new PostDraftTermState(Map.of(
                        "Louki", "露姬",
                        "Harbor Master", "港务长"
                ), List.of()),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "merged draft"
        );
    }

    private static ProjectKnowledgeBase knowledgeBase(String projectId) {
        return new ProjectKnowledgeBase(
                projectId,
                List.of(
                        new KnowledgeCard(
                                "card-1",
                                KnowledgeCardType.CULTURAL_BACKGROUND,
                                "festival customs",
                                "local festival customs",
                                List.of("festival", "customs"),
                                List.of("festival"),
                                List.of("source-1"),
                                "project",
                                List.of("chunk-3")
                        )
                ),
                List.of(new CandidateTerm("festival", List.of("festival"), "noun", "test term"))
        );
    }

    private static PostDraftChunkRecord chunk(String chunkId,
                                              int sequence,
                                              String blockId,
                                              String sourceText,
                                              String translatedText,
                                              String commentary,
                                              List<TranslationDecisionNote> decisionNotes,
                                              String nextChunkConnection) {
        return new PostDraftChunkRecord(
                chunkId,
                sequence,
                blockId,
                sourceText,
                translatedText,
                commentary,
                decisionNotes,
                Map.of(),
                List.of(),
                new ChunkTransitionNote("before", nextChunkConnection, false)
        );
    }

    private static final class StubKnowledgeRetrievalService implements KnowledgeRetrievalService {
        @Override
        public KnowledgeRetrievalResult retrieve(String projectId,
                                                  io.quillloom.domain.knowledge.ProjectKnowledgeBase preferredKnowledgeBase,
                                                  KnowledgeRetrievalQuery query) {
            return new KnowledgeRetrievalResult(List.of(
                    new KnowledgeCard(
                            "card-1",
                            KnowledgeCardType.CULTURAL_BACKGROUND,
                            "festival customs",
                            "local festival customs",
                            List.of("festival", "customs"),
                            List.of("festival"),
                            List.of("source-1"),
                            "project",
                            List.of("chunk-3")
                    )
            ));
        }
    }
}
