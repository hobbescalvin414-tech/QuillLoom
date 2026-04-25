package io.quillloom.domain.translation;

/**
 * ???????????????????????????
 */
public record TranslationRuntimeOptions(
        boolean allowKnowledgeCards,
        boolean preserveParagraphBreaks,
        boolean emitHandoffNotes,
        int sourceContextWindowSize,
        int summaryContextWindowSize
) {

    public TranslationRuntimeOptions {
        if (sourceContextWindowSize < 1) {
            throw new IllegalArgumentException("?????????? 1?");
        }
        if (summaryContextWindowSize < sourceContextWindowSize) {
            throw new IllegalArgumentException("???????????????????");
        }
    }

    public static TranslationRuntimeOptions defaults() {
        return new TranslationRuntimeOptions(true, true, true, 1, 2);
    }
}
