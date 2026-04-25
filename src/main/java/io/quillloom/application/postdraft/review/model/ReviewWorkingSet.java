package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public record ReviewWorkingSet(
        String anchorChunkId,
        List<String> chunkIds
) {

    public ReviewWorkingSet {
        String normalizedAnchor = normalizeChunkId(anchorChunkId);
        chunkIds = normalizeChunkIds(normalizedAnchor, chunkIds);
        if (normalizedAnchor == null && !chunkIds.isEmpty()) {
            normalizedAnchor = chunkIds.get(0);
        }
        if (normalizedAnchor == null) {
            normalizedAnchor = "";
        }
        if (!normalizedAnchor.isEmpty() && chunkIds.isEmpty()) {
            chunkIds = List.of(normalizedAnchor);
        }
        anchorChunkId = normalizedAnchor;
    }

    public static ReviewWorkingSet empty() {
        return new ReviewWorkingSet("", List.of());
    }

    public static ReviewWorkingSet fromAnchor(String chunkId) {
        String normalizedChunkId = requireChunkId(chunkId, "chunkId must not be blank");
        return new ReviewWorkingSet(normalizedChunkId, List.of(normalizedChunkId));
    }

    public ReviewWorkingSet expandTo(List<String> nextChunkIds) {
        return new ReviewWorkingSet(anchorChunkId, nextChunkIds);
    }

    public boolean isEmpty() {
        return chunkIds.isEmpty();
    }

    private static List<String> normalizeChunkIds(String anchorChunkId, List<String> chunkIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (anchorChunkId != null && !anchorChunkId.isBlank()) {
            normalized.add(anchorChunkId);
        }
        if (chunkIds != null) {
            for (String chunkId : chunkIds) {
                String normalizedChunkId = normalizeChunkId(chunkId);
                if (normalizedChunkId != null) {
                    normalized.add(normalizedChunkId);
                }
            }
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    private static String normalizeChunkId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return null;
        }
        return chunkId.trim();
    }

    private static String requireChunkId(String chunkId, String message) {
        String normalizedChunkId = normalizeChunkId(chunkId);
        if (normalizedChunkId == null) {
            throw new IllegalArgumentException(message);
        }
        return normalizedChunkId;
    }
}
