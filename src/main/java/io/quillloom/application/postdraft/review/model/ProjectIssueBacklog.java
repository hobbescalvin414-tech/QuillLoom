package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public record ProjectIssueBacklog(
        List<DeferredReviewIssue> openIssues
) {

    public ProjectIssueBacklog {
        openIssues = deduplicate(openIssues);
    }

    public static ProjectIssueBacklog empty() {
        return new ProjectIssueBacklog(List.of());
    }

    public ProjectIssueBacklog add(DeferredReviewIssue issue) {
        ArrayList<DeferredReviewIssue> updated = new ArrayList<>(openIssues);
        updated.add(Objects.requireNonNull(issue, "issue"));
        return new ProjectIssueBacklog(updated);
    }

    public ProjectIssueBacklog merge(ProjectIssueBacklog other) {
        if (other == null || other.openIssues.isEmpty()) {
            return this;
        }
        ArrayList<DeferredReviewIssue> updated = new ArrayList<>(openIssues);
        updated.addAll(other.openIssues);
        return new ProjectIssueBacklog(updated);
    }

    public boolean isEmpty() {
        return openIssues.isEmpty();
    }

    private static List<DeferredReviewIssue> deduplicate(List<DeferredReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, DeferredReviewIssue> deduplicated = new LinkedHashMap<>();
        for (DeferredReviewIssue issue : issues) {
            DeferredReviewIssue nextIssue = Objects.requireNonNull(issue, "issue");
            deduplicated.putIfAbsent(nextIssue.issueId(), nextIssue);
        }
        return List.copyOf(deduplicated.values());
    }
}
