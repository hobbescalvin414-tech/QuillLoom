package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record ReviewBoundaryWindow(
        List<ReviewContextChunkSnapshot> snapshots
) {

    private static final Comparator<ReviewContextChunkSnapshot> CANONICAL_ORDER =
            Comparator.comparingInt(ReviewContextChunkSnapshot::sequence)
                    .thenComparing(ReviewContextChunkSnapshot::chunkId);

    public ReviewBoundaryWindow {
        snapshots = normalize(snapshots);
    }

    public static ReviewBoundaryWindow empty() {
        return new ReviewBoundaryWindow(List.of());
    }

    public Optional<String> leftEdgeChunkId() {
        return snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.get(0).chunkId());
    }

    public Optional<String> rightEdgeChunkId() {
        return snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.get(snapshots.size() - 1).chunkId());
    }

    private static List<ReviewContextChunkSnapshot> normalize(List<ReviewContextChunkSnapshot> snapshots) {
        if (snapshots == null) {
            return List.of();
        }
        ArrayList<ReviewContextChunkSnapshot> normalized = new ArrayList<>(snapshots);
        normalized.removeIf(snapshot -> snapshot == null);
        normalized.sort(CANONICAL_ORDER);
        return List.copyOf(normalized);
    }
}
