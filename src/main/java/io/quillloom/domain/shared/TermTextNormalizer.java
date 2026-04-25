package io.quillloom.domain.shared;

import java.util.Locale;

public final class TermTextNormalizer {

    private TermTextNormalizer() {
    }

    public static String displayText(String value) {
        return value == null ? "" : value.trim();
    }

    public static String keyText(String value) {
        return displayText(value).toLowerCase(Locale.ROOT);
    }

    public static String pairKey(String sourceTerm, String targetTerm) {
        return keyText(sourceTerm) + "|" + keyText(targetTerm);
    }
}
