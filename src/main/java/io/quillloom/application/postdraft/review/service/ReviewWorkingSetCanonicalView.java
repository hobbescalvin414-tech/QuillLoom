package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class ReviewWorkingSetCanonicalView {

    private ReviewWorkingSetCanonicalView() {
    }

    public static List<String> chunkIds(ProjectReviewRuntimeSession runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return runtime.currentFocusSession()
                .map(ReviewWorkingSetCanonicalView::chunkIds)
                .orElseGet(runtime.workingSet()::chunkIds);
    }

    public static List<String> chunkIds(PostDraftReviewSession session) {
        Objects.requireNonNull(session, "session");
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (ReviewContextChunkSnapshot snapshot : session.workingSetContext().snapshots()) {
            ordered.add(snapshot.chunkId());
        }
        for (String chunkId : session.workingSet().chunkIds()) {
            if (chunkId != null && !chunkId.isBlank()) {
                ordered.add(chunkId);
            }
        }
        return List.copyOf(new ArrayList<>(ordered));
    }

    public static List<ReviewContextChunkSnapshot> snapshots(PostDraftReviewSession session) {
        Objects.requireNonNull(session, "session");
        return session.workingSetContext().snapshots();
    }
}
