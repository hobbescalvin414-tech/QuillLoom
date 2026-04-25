package io.quillloom.infrastructure.preprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalizedTextViewTest {

    @Test
    void shouldPreserveParagraphStructureWhileFlatteningIntraParagraphWhitespace() {
        String raw = "Patrick Modiano\n\nDans le cafe\nde la jeunesse\nperdue\n\n\nEditions Gallimard, 2007.";

        NormalizedTextView view = NormalizedTextView.from(raw);

        assertEquals(
                "Patrick Modiano\n\nDans le cafe de la jeunesse perdue\n\nEditions Gallimard, 2007.",
                view.normalizedText()
        );
    }

    @Test
    void shouldMapNormalizedRangeBackToRawOffsets() {
        String raw = "Alpha line\nBeta line\n\nGamma line";
        NormalizedTextView view = NormalizedTextView.from(raw);
        int start = view.find("Alpha line Beta line", 0);
        int rawEnd = view.rawExclusiveEndFor(start, start + "Alpha line Beta line".length());

        assertEquals(raw.indexOf("Beta line") + "Beta line".length(), rawEnd);
    }
}
