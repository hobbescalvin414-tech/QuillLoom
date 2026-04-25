package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.ToolCallSignature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolCallSignatureTest {

    @Test
    void shouldNormalizeReadConfirmedTermsSignatureCaseInsensitively() {
        ToolCallSignature signature = ToolCallSignature.forReadConfirmedTerms(List.of(" Le Conde ", "LE CONDE"));

        assertEquals("read_confirmed_terms", signature.toolName());
        assertEquals("read_confirmed_terms:sourceTerms=[le conde]", signature.key());
        assertEquals("read_confirmed_terms sourceTerms=[Le Conde]", signature.display());
    }

    @Test
    void shouldSortReadConfirmedTermsForStableSignature() {
        ToolCallSignature left = ToolCallSignature.forReadConfirmedTerms(List.of("Zoo", "Alpha"));
        ToolCallSignature right = ToolCallSignature.forReadConfirmedTerms(List.of("alpha", "zoo"));

        assertEquals(left.key(), right.key());
    }

    @Test
    void shouldUseSameSignatureForConfirmedTermHitAndLookupMiss() {
        ToolCallSignature hit = ToolCallSignature.forReadConfirmedTerms(List.of("Le Conde"));
        ToolCallSignature miss = ToolCallSignature.forReadConfirmedTerms(List.of(" le conde "));

        assertEquals(hit.key(), miss.key());
    }

    @Test
    void shouldRejectEmptyReadConfirmedTermsSignature() {
        assertThrows(IllegalArgumentException.class, () -> ToolCallSignature.forReadConfirmedTerms(List.of(" ")));
    }
}
