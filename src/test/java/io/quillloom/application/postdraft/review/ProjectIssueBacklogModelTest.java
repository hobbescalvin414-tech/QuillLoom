package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.DeferredReviewIssue;
import io.quillloom.application.postdraft.review.model.ProjectIssueBacklog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectIssueBacklogModelTest {

    @Test
    void shouldRegisterDeferredIssueWithoutClosingProject() {
        ProjectIssueBacklog backlog = ProjectIssueBacklog.empty()
                .add(new DeferredReviewIssue("issue-1", "chunk-10", "alias unresolved"));

        assertEquals(1, backlog.openIssues().size());
        assertFalse(backlog.isEmpty());
    }

    @Test
    void shouldDefensivelyCopyIssueList() {
        ArrayList<DeferredReviewIssue> issues = new ArrayList<>(List.of(
                new DeferredReviewIssue("issue-1", "chunk-10", "alias unresolved")
        ));

        ProjectIssueBacklog backlog = new ProjectIssueBacklog(issues);
        issues.add(new DeferredReviewIssue("issue-2", "chunk-11", "term unresolved"));

        assertEquals(1, backlog.openIssues().size());
        assertThrows(UnsupportedOperationException.class,
                () -> backlog.openIssues().add(new DeferredReviewIssue("issue-3", "chunk-12", "x")));
    }

    @Test
    void shouldDeduplicateIssuesByIssueId() {
        ProjectIssueBacklog backlog = ProjectIssueBacklog.empty()
                .add(new DeferredReviewIssue("issue-1", "chunk-10", "alias unresolved"))
                .add(new DeferredReviewIssue("issue-1", "chunk-11", "same logical issue"));

        assertEquals(1, backlog.openIssues().size());
        assertEquals("chunk-10", backlog.openIssues().get(0).relatedChunkId());
    }
}
