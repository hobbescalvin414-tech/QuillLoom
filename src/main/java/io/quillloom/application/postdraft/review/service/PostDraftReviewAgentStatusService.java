package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewProjectStatusView;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.StoredReviewSession;
import io.quillloom.application.postdraft.review.port.out.ReviewSessionStore;

import java.util.Objects;
import java.util.Optional;

public class PostDraftReviewAgentStatusService {

    private final PostDraftReviewAgentService service;
    private final ReviewSessionStore reviewSessionStore;

    public PostDraftReviewAgentStatusService(PostDraftReviewAgentService service,
                                             ReviewSessionStore reviewSessionStore) {
        this.service = Objects.requireNonNull(service, "service");
        this.reviewSessionStore = Objects.requireNonNull(reviewSessionStore, "reviewSessionStore");
    }

    public Optional<PostDraftReviewProjectStatusView> loadStatus(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        Optional<ProjectReviewRuntimeSession> inMemoryRuntime = service.findActiveRuntime(projectId);
        if (inMemoryRuntime.isPresent()) {
            return inMemoryRuntime.map(this::toView);
        }
        return reviewSessionStore.load(projectId)
                .map(StoredReviewSession::runtime)
                .map(this::toView);
    }

    private PostDraftReviewProjectStatusView toView(ProjectReviewRuntimeSession runtime) {
        return new PostDraftReviewProjectStatusView(
                runtime.projectId(),
                runtime.status().name(),
                runtime.stopReason().name(),
                runtime.currentFocusChunkId().orElse(""),
                runtime.completedChunkOutcomes().size(),
                runtime.status() == io.quillloom.application.postdraft.review.model.ProjectReviewStatus.WAITING_HUMAN,
                runtime.humanReviewRequest().map(request -> request.questionForHuman()).orElse("")
        );
    }
}
