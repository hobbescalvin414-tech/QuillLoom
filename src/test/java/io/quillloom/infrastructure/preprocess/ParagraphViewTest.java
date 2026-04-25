package io.quillloom.infrastructure.preprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParagraphViewTest {

    @Test
    void shouldSplitRawTextIntoParagraphSegments() {
        String raw = "Patrick Modiano\n\nDans le cafe\nde la jeunesse\nperdue\n\n\nEditions Gallimard, 2007.";

        ParagraphView view = ParagraphView.from(raw);

        assertEquals(3, view.paragraphs().size());

        ParagraphSegment p1 = view.paragraphAt(1);
        assertEquals("Patrick Modiano", p1.rawText());
        assertEquals("Patrick Modiano", p1.normalizedText());

        ParagraphSegment p2 = view.paragraphAt(2);
        assertEquals("Dans le cafe\nde la jeunesse\nperdue", p2.rawText());
        assertEquals("Dans le cafe de la jeunesse perdue", p2.normalizedText());

        ParagraphSegment p3 = view.paragraphAt(3);
        assertEquals("Editions Gallimard, 2007.", p3.rawText());
        assertTrue(p3.startOffset() > p2.endOffset());
    }

    @Test
    void shouldRenderLightweightIndexedParagraphView() {
        ParagraphView view = ParagraphView.from("A first paragraph.\n\nA second paragraph.");

        assertEquals("P1: A first paragraph.\n\nP2: A second paragraph.", view.renderIndexedView());
    }
}
