package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.assembler.PostDraftReviewPackageAssembler;
import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.postdraft.PostDraftBlockIndex;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import io.quillloom.infrastructure.postdraft.InMemoryPostDraftReviewPackageRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryBackedPostDraftReviewAgentTermWriterTest {

    @Test
    void shouldAppendConfirmedTermsIntoReviewPackage() {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        repository.save(reviewPackage());
        RepositoryBackedPostDraftReviewAgentReader reader = new RepositoryBackedPostDraftReviewAgentReader(
                repository,
                new io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository() {
                    @Override
                    public Optional<io.quillloom.domain.knowledge.ProjectKnowledgeBase> load(String projectId) {
                        return Optional.empty();
                    }

                    @Override
                    public void save(io.quillloom.domain.knowledge.ProjectKnowledgeBase knowledgeBase) {
                    }
                },
                new io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler(),
                new io.quillloom.application.translation.port.out.KnowledgeRetrievalService() {
                    @Override
                    public io.quillloom.application.translation.model.KnowledgeRetrievalResult retrieve(
                            String projectId,
                            io.quillloom.domain.knowledge.ProjectKnowledgeBase preferredKnowledgeBase,
                            io.quillloom.application.translation.model.KnowledgeRetrievalQuery query) {
                        return new io.quillloom.application.translation.model.KnowledgeRetrievalResult(List.of());
                    }
                }
        );
        RepositoryBackedPostDraftReviewAgentTermWriter writer = new RepositoryBackedPostDraftReviewAgentTermWriter(
                repository,
                new PostDraftReviewPackageAssembler(),
                reader
        );

        Map<String, String> applied = writer.recordConfirmedTerms(
                "project-1",
                Map.of("Louki", "露姬")
        );

        PostDraftReviewPackage updated = repository.load("project-1").orElseThrow();
        assertEquals(Map.of("Louki", "露姬"), applied);
        assertEquals("露姬", updated.termState().effectiveConfirmedTerms().get("Louki"));
        assertTrue(updated.glossarySnapshot().hardEntries().stream()
                .anyMatch(entry -> entry.sourceTerm().equals("Louki") && entry.targetTerm().equals("露姬")));
    }

    @Test
    void shouldRejectConflictingConfirmedTermUpdate() {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        repository.save(reviewPackage());
        RepositoryBackedPostDraftReviewAgentReader reader = new RepositoryBackedPostDraftReviewAgentReader(
                repository,
                new io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository() {
                    @Override
                    public Optional<io.quillloom.domain.knowledge.ProjectKnowledgeBase> load(String projectId) {
                        return Optional.empty();
                    }

                    @Override
                    public void save(io.quillloom.domain.knowledge.ProjectKnowledgeBase knowledgeBase) {
                    }
                },
                new io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler(),
                new io.quillloom.application.translation.port.out.KnowledgeRetrievalService() {
                    @Override
                    public io.quillloom.application.translation.model.KnowledgeRetrievalResult retrieve(
                            String projectId,
                            io.quillloom.domain.knowledge.ProjectKnowledgeBase preferredKnowledgeBase,
                            io.quillloom.application.translation.model.KnowledgeRetrievalQuery query) {
                        return new io.quillloom.application.translation.model.KnowledgeRetrievalResult(List.of());
                    }
                }
        );
        RepositoryBackedPostDraftReviewAgentTermWriter writer = new RepositoryBackedPostDraftReviewAgentTermWriter(
                repository,
                new PostDraftReviewPackageAssembler(),
                reader
        );
        writer.recordConfirmedTerms("project-1", Map.of("Louki", "露姬"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> writer.recordConfirmedTerms("project-1", Map.of("Louki", "露琪"))
        );

        assertTrue(exception.getMessage().contains("confirmed_term_conflict"));
    }

    @Test
    void shouldDeduplicateSameConfirmedTermBySourceAndTargetKey() {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        repository.save(reviewPackage(new PostDraftTermState(Map.of("Le Condé", "孔代咖啡馆"), List.of())));
        RepositoryBackedPostDraftReviewAgentReader reader = reader(repository);
        RepositoryBackedPostDraftReviewAgentTermWriter writer = new RepositoryBackedPostDraftReviewAgentTermWriter(
                repository,
                new PostDraftReviewPackageAssembler(),
                reader
        );

        Map<String, String> applied = writer.recordConfirmedTerms("project-1", Map.of("le condé", "孔代咖啡馆"));

        PostDraftReviewPackage updated = repository.load("project-1").orElseThrow();
        assertEquals(Map.of("Le Condé", "孔代咖啡馆"), updated.termState().effectiveConfirmedTerms());
        assertEquals(Map.of("Le Condé", "孔代咖啡馆"), applied);
    }

    @Test
    void shouldRejectConflictingConfirmedTermBySourceKey() {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        repository.save(reviewPackage(new PostDraftTermState(Map.of("Le Condé", "孔代咖啡馆"), List.of())));
        RepositoryBackedPostDraftReviewAgentTermWriter writer = new RepositoryBackedPostDraftReviewAgentTermWriter(
                repository,
                new PostDraftReviewPackageAssembler(),
                reader(repository)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> writer.recordConfirmedTerms("project-1", Map.of("le condé", "勒孔代咖啡馆"))
        );

        assertTrue(exception.getMessage().contains("confirmed_term_conflict"));
        assertTrue(exception.getMessage().contains("sourceTerm=le condé"));
        assertTrue(exception.getMessage().contains("existing=孔代咖啡馆"));
        assertTrue(exception.getMessage().contains("incoming=勒孔代咖啡馆"));
    }

    private static PostDraftReviewPackage reviewPackage() {
        return reviewPackage(new PostDraftTermState(Map.of(), List.of()));
    }

    private static PostDraftReviewPackage reviewPackage(PostDraftTermState termState) {
        return new PostDraftReviewPackage(
                "project-1",
                "v1",
                "en",
                "zh",
                "digest-1",
                Instant.parse("2026-04-15T00:00:00Z"),
                List.of(new PostDraftChunkRecord(
                        "chunk-1",
                        1,
                        "block-1",
                        "Louki looked back.",
                        "Louki 回头看了一眼。",
                        "note",
                        List.of(),
                        Map.of(),
                        List.of(),
                        null
                )),
                List.of(new PostDraftBlockIndex("block-1", "summary", List.of("chunk-1"))),
                termState,
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "merged"
        );
    }

    private static RepositoryBackedPostDraftReviewAgentReader reader(InMemoryPostDraftReviewPackageRepository repository) {
        return new RepositoryBackedPostDraftReviewAgentReader(
                repository,
                new io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository() {
                    @Override
                    public Optional<io.quillloom.domain.knowledge.ProjectKnowledgeBase> load(String projectId) {
                        return Optional.empty();
                    }

                    @Override
                    public void save(io.quillloom.domain.knowledge.ProjectKnowledgeBase knowledgeBase) {
                    }
                },
                new io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler(),
                new io.quillloom.application.translation.port.out.KnowledgeRetrievalService() {
                    @Override
                    public io.quillloom.application.translation.model.KnowledgeRetrievalResult retrieve(
                            String projectId,
                            io.quillloom.domain.knowledge.ProjectKnowledgeBase preferredKnowledgeBase,
                            io.quillloom.application.translation.model.KnowledgeRetrievalQuery query) {
                        return new io.quillloom.application.translation.model.KnowledgeRetrievalResult(List.of());
                    }
                }
        );
    }
}
