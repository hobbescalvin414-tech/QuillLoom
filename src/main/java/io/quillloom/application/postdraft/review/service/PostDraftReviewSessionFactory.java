package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.command.StartPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProblemType;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class PostDraftReviewSessionFactory {

    public PostDraftReviewSession create(StartPostDraftReviewAgentCommand command,
                                         PostDraftReviewPackage reviewPackage) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        Objects.requireNonNull(reviewPackage, "reviewPackage");
        if (!command.projectId().equals(reviewPackage.projectId())) {
            throw new IllegalArgumentException("command.projectId must match reviewPackage.projectId");
        }
        boolean chunkExists = reviewPackage.chunks().stream()
                .anyMatch(chunk -> command.focus().chunkId().equals(chunk.chunkId()));
        if (!chunkExists) {
            throw new IllegalArgumentException("command.focus.chunkId must exist in reviewPackage.chunks");
        }
        return PostDraftReviewSession.initial(command.projectId(), command.focus(), command.operatorNote());
    }

    public PostDraftReviewSession createProjectFocusSession(String projectId,
                                                            String operatorNote,
                                                            PostDraftChunkRecord chunk,
                                                            Set<ReviewProblemType> problemTypes,
                                                            List<String> evidenceSummaries) {
        Objects.requireNonNull(chunk, "chunk");
        return PostDraftReviewSession.investigating(
                projectId,
                ReviewFocus.forChunk(chunk.chunkId()),
                operatorNote,
                problemTypes == null ? Set.of() : problemTypes,
                evidenceSummaries == null ? List.of() : evidenceSummaries
        );
    }
}
