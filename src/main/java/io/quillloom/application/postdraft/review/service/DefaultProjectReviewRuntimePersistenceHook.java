package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewStatus;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentWriter;
import io.quillloom.application.postdraft.review.port.out.ReviewSessionStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class DefaultProjectReviewRuntimePersistenceHook implements ProjectReviewRuntimePersistenceHook {

    private final PostDraftReviewAgentWriter writer;
    private final ReviewSessionStore reviewSessionStore;

    public DefaultProjectReviewRuntimePersistenceHook(PostDraftReviewAgentWriter writer,
                                                      ReviewSessionStore reviewSessionStore) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.reviewSessionStore = Objects.requireNonNull(reviewSessionStore, "reviewSessionStore");
    }

    @Override
    public void afterTransition(ProjectReviewRuntimeSession previousRuntime,
                                ProjectReviewRuntimeSession currentRuntime) {
        Objects.requireNonNull(previousRuntime, "previousRuntime");
        Objects.requireNonNull(currentRuntime, "currentRuntime");

        List<ProjectChunkReviewOutcome> newOutcomes = findNewOutcomes(previousRuntime, currentRuntime);
        if (!newOutcomes.isEmpty()) {
            writer.writeCompletedChunks(currentRuntime.projectId(), newOutcomes);
        }

        if (currentRuntime.status() == ProjectReviewStatus.WAITING_HUMAN) {
            reviewSessionStore.save(currentRuntime);
            return;
        }

        if (currentRuntime.status() == ProjectReviewStatus.FAILED) {
            reviewSessionStore.save(currentRuntime);
            return;
        }

        if (currentRuntime.status() == ProjectReviewStatus.COMPLETED) {
            writer.writeMergedDraftFromProjectChunks(currentRuntime.projectId());
            reviewSessionStore.delete(currentRuntime.projectId());
        }
    }

    private List<ProjectChunkReviewOutcome> findNewOutcomes(ProjectReviewRuntimeSession previousRuntime,
                                                            ProjectReviewRuntimeSession currentRuntime) {
        Set<String> previousChunkIds = new LinkedHashSet<>();
        for (ProjectChunkReviewOutcome outcome : previousRuntime.completedChunkOutcomes()) {
            previousChunkIds.add(outcome.chunkId());
        }
        ArrayList<ProjectChunkReviewOutcome> newOutcomes = new ArrayList<>();
        for (ProjectChunkReviewOutcome outcome : currentRuntime.completedChunkOutcomes()) {
            if (!previousChunkIds.contains(outcome.chunkId())) {
                newOutcomes.add(outcome);
            }
        }
        return List.copyOf(newOutcomes);
    }
}
