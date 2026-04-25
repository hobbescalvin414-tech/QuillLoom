package io.quillloom.domain.shared;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TermTextNormalizerTest {

    @Test
    void shouldNormalizeKeysWithLocaleRoot() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            assertEquals("i", TermTextNormalizer.keyText("I"));
            assertEquals("le condé|孔代咖啡馆", TermTextNormalizer.pairKey(" Le Condé ", " 孔代咖啡馆 "));
            assertEquals("Le Condé", TermTextNormalizer.displayText(" Le Condé "));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
