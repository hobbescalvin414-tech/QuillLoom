package io.quillloom.domain.preprocess;

import java.util.List;

/**
 * Collection of stable chunk-level structured annotations.
 */
public record ChunkAnnotationBundle(
        List<ChunkAnnotation> chunks
) {
}
