package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Comparator;

public record ReviewWorkingSetContext(
        List<ReviewContextChunkSnapshot> snapshots
) {

    private static final Comparator<ReviewContextChunkSnapshot> CANONICAL_ORDER =
            Comparator.comparingInt(ReviewContextChunkSnapshot::sequence)
                    .thenComparing(ReviewContextChunkSnapshot::chunkId);

    public ReviewWorkingSetContext {
        snapshots = normalize(snapshots);
    }

    public static ReviewWorkingSetContext empty() {
        return new ReviewWorkingSetContext(List.of());
    }

    public static ReviewWorkingSetContext of(List<ReviewContextChunkSnapshot> snapshots) {
        return new ReviewWorkingSetContext(snapshots);
    }

    public ReviewWorkingSetContext merge(List<ReviewContextChunkSnapshot> incomingSnapshots) {
        LinkedHashMap<String, ReviewContextChunkSnapshot> merged = new LinkedHashMap<>();
        for (ReviewContextChunkSnapshot snapshot : snapshots) {
            merged.put(snapshot.chunkId(), snapshot);
        }
        for (ReviewContextChunkSnapshot snapshot : incomingSnapshots == null ? List.<ReviewContextChunkSnapshot>of() : incomingSnapshots) {
            if (snapshot != null) {
                merged.put(snapshot.chunkId(), snapshot);
            }
        }
        return new ReviewWorkingSetContext(new ArrayList<>(merged.values()));
    }

    private static List<ReviewContextChunkSnapshot> normalize(List<ReviewContextChunkSnapshot> snapshots) {
        if (snapshots == null) {
            return List.of();
        }
        LinkedHashMap<String, ReviewContextChunkSnapshot> deduped = new LinkedHashMap<>();
        for (ReviewContextChunkSnapshot snapshot : snapshots) {
            if (snapshot != null) {
                deduped.put(snapshot.chunkId(), snapshot);
            }
        }
        ArrayList<ReviewContextChunkSnapshot> normalized = new ArrayList<>(deduped.values());
        normalized.sort(CANONICAL_ORDER);
        return List.copyOf(normalized);
    }
}
