package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.List;

public record ReviewContextChunkSnapshot(
        String chunkId,
        int sequence,
        String sourceText,
        String translatedText,
        String translatorCommentary,
        List<String> decisionNotes,
        List<String> confirmedTermUpdates,
        String transitionNote,
        boolean anchor
) {

    public ReviewContextChunkSnapshot {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
        chunkId = chunkId.trim();
        sourceText = normalizeText(sourceText);
        translatedText = normalizeText(translatedText);
        translatorCommentary = normalizeText(translatorCommentary);
        decisionNotes = normalizeList(decisionNotes);
        confirmedTermUpdates = normalizeList(confirmedTermUpdates);
        transitionNote = normalizeText(transitionNote);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        ArrayList<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }
}
