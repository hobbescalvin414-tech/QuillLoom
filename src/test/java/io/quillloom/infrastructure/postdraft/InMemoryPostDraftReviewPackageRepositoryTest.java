package io.quillloom.infrastructure.postdraft;

import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryPostDraftReviewPackageRepositoryTest {

    @Test
    void shouldSaveAndLoadByProjectId() {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        PostDraftReviewPackage expected = new PostDraftReviewPackage(
                "project-1",
                "v1",
                "fr",
                "zh",
                "digest-1",
                Instant.parse("2026-04-14T10:15:30Z"),
                List.of(),
                List.of(),
                new PostDraftTermState(Map.of("Louki", "露姬"), List.of()),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "merged"
        );

        repository.save(expected);

        PostDraftReviewPackage actual = repository.load("project-1").orElseThrow();
        assertEquals("project-1", actual.projectId());
        assertEquals("露姬", actual.termState().effectiveConfirmedTerms().get("Louki"));
    }
}
