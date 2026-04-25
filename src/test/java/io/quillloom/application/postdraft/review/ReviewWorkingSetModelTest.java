package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.ReviewWorkingSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewWorkingSetModelTest {

    @Test
    void shouldExpandWorkingSetFromAnchorChunk() {
        ReviewWorkingSet workingSet = ReviewWorkingSet.fromAnchor("chunk-10")
                .expandTo(List.of("chunk-10", "chunk-11", "chunk-12"));

        assertEquals("chunk-10", workingSet.anchorChunkId());
        assertEquals(List.of("chunk-10", "chunk-11", "chunk-12"), workingSet.chunkIds());
    }

    @Test
    void shouldKeepAnchorChunkWhenExpansionOmitsIt() {
        ReviewWorkingSet workingSet = ReviewWorkingSet.fromAnchor("chunk-10")
                .expandTo(List.of("chunk-11", "chunk-12"));

        assertEquals(List.of("chunk-10", "chunk-11", "chunk-12"), workingSet.chunkIds());
    }

    @Test
    void shouldNotLeakChunkIdsThroughConstructorOrAccessor() {
        ArrayList<String> chunkIds = new ArrayList<>(List.of("chunk-10", "chunk-11"));

        ReviewWorkingSet workingSet = new ReviewWorkingSet("chunk-10", chunkIds);
        chunkIds.add("chunk-12");

        assertEquals(List.of("chunk-10", "chunk-11"), workingSet.chunkIds());
        assertThrows(UnsupportedOperationException.class, () -> workingSet.chunkIds().add("chunk-13"));
    }
}
