package io.quillloom.application.postdraft.review.model;

public record ReviewFocus(
        String chunkId
) {

    public ReviewFocus {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
    }

    public static ReviewFocus forChunk(String chunkId) {
        return new ReviewFocus(chunkId);
    }
}
