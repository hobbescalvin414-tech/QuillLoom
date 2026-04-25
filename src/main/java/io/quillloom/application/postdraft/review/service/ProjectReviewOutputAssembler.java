package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewProjectStopReason;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class ProjectReviewOutputAssembler {

    public PostDraftReviewAgentResult assemble(ProjectReviewRuntimeSession runtime) {
        Objects.requireNonNull(runtime, "runtime");

        String mergedTranslation = runtime.completedChunkOutcomes().stream()
                .map(ProjectChunkReviewOutcome::finalTranslation)
                .collect(Collectors.joining("\n\n"));

        ArrayList<String> evidenceSummaries = new ArrayList<>();
        runtime.completedChunkOutcomes().forEach(outcome ->
                evidenceSummaries.add("completed[" + outcome.chunkId() + "]=" + outcome.strategy()));
        runtime.issueBacklog().openIssues().forEach(issue ->
                evidenceSummaries.add("backlog[" + issue.issueId() + "]=" + issue.summary()));
        evidenceSummaries.addAll(runtime.processTrail());

        String processNote = "completedChunkCount=" + runtime.completedChunkOutcomes().size()
                + ", pendingChunkCount=" + runtime.pendingChunkIds().size()
                + ", openIssueCount=" + runtime.issueBacklog().openIssues().size()
                + ", stopReason=" + normalizeStopReason(runtime.stopReason());

        ReviewStrategy summaryStrategy = runtime.humanReviewRequest()
                .map(request -> request.processSummary().strategy())
                .orElseGet(() -> runtime.completedChunkOutcomes().isEmpty()
                        ? ReviewStrategy.KEEP
                        : runtime.completedChunkOutcomes().get(runtime.completedChunkOutcomes().size() - 1).strategy());

        ReviewProcessSummary processSummary = new ReviewProcessSummary(
                runtime.projectId(),
                resolveSummaryFocus(runtime),
                summaryStrategy,
                Set.of(),
                List.copyOf(evidenceSummaries),
                processNote
        );

        return PostDraftReviewAgentResult.forProject(
                mergedTranslation,
                runtime.completedChunkOutcomes(),
                processSummary,
                runtime.humanReviewRequest()
        );
    }

    private String normalizeStopReason(ReviewProjectStopReason stopReason) {
        if (stopReason == null) {
            return "unknown";
        }
        return stopReason.name().toLowerCase(Locale.ROOT);
    }

    private ReviewFocus resolveSummaryFocus(ProjectReviewRuntimeSession runtime) {
        if (runtime.humanReviewRequest().isPresent()) {
            return runtime.humanReviewRequest().orElseThrow().focus();
        }
        if (!runtime.completedChunkOutcomes().isEmpty()) {
            return runtime.completedChunkOutcomes()
                    .get(runtime.completedChunkOutcomes().size() - 1)
                    .processSummary()
                    .focus();
        }
        if (runtime.currentFocusSession().isPresent()) {
            return runtime.currentFocusSession().orElseThrow().focus();
        }
        if (runtime.currentFocusChunkId().isPresent()) {
            return ReviewFocus.forChunk(runtime.currentFocusChunkId().orElseThrow());
        }
        if (!runtime.pendingChunkIds().isEmpty()) {
            return ReviewFocus.forChunk(runtime.pendingChunkIds().get(0));
        }
        throw new IllegalStateException("Project summary requires at least one real chunk focus");
    }
}
