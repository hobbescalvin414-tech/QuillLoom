package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationDecisionNote;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

final class ReviewChunkSnapshotFormatter {

    private ReviewChunkSnapshotFormatter() {
    }

    static String renderAnchorChunk(PostDraftChunkRecord chunk) {
        return "anchorChunk={" + renderChunkBody(chunk) + "}";
    }

    static String renderContextChunk(PostDraftChunkRecord chunk) {
        return "contextChunk={" + renderChunkBody(chunk) + "}";
    }

    static ReviewContextChunkSnapshot toContextSnapshot(PostDraftChunkRecord chunk, boolean anchor) {
        return new ReviewContextChunkSnapshot(
                chunk.chunkId(),
                chunk.sequence(),
                safe(chunk.sourceText()),
                safe(chunk.effectiveTranslatedText()),
                safe(chunk.translatorCommentary()),
                renderDecisionNoteList(chunk.decisionNotes()),
                renderConfirmedTermUpdateList(chunk.confirmedTermUpdates()),
                renderTransitionNote(chunk.transitionNote()),
                anchor
        );
    }

    private static String renderChunkBody(PostDraftChunkRecord chunk) {
        StringJoiner joiner = new StringJoiner(", ");
        joiner.add("chunkId=" + safe(chunk.chunkId()));
        joiner.add("sourceText=" + safe(chunk.sourceText()));
        joiner.add("translatedText=" + safe(chunk.effectiveTranslatedText()));
        joiner.add("translatorCommentary=" + safe(chunk.translatorCommentary()));
        joiner.add("decisionNotes=" + renderDecisionNotes(chunk.decisionNotes()));
        joiner.add("confirmedTermUpdates=" + renderConfirmedTermUpdates(chunk.confirmedTermUpdates()));
        joiner.add("transitionNote=" + renderTransitionNote(chunk.transitionNote()));
        return joiner.toString();
    }

    private static String renderDecisionNotes(List<TranslationDecisionNote> notes) {
        return renderDecisionNoteList(notes).toString();
    }

    private static List<String> renderDecisionNoteList(List<TranslationDecisionNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return List.of();
        }
        ArrayList<String> rendered = new ArrayList<>();
        for (TranslationDecisionNote note : notes) {
            if (note == null) {
                continue;
            }
            rendered.add("{type=" + safe(note.type())
                    + ", sourceAnchor=" + safe(note.sourceAnchor())
                    + ", description=" + safe(note.description())
                    + ", recommendation=" + safe(note.recommendation())
                    + "}");
        }
        return List.copyOf(rendered);
    }

    private static String renderConfirmedTermUpdates(Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return "{}";
        }
        return updates.toString();
    }

    private static List<String> renderConfirmedTermUpdateList(Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return List.of();
        }
        ArrayList<String> rendered = new ArrayList<>();
        updates.forEach((source, target) -> rendered.add(safe(source) + "->" + safe(target)));
        return List.copyOf(rendered);
    }

    private static String renderTransitionNote(ChunkTransitionNote note) {
        if (note == null) {
            return "{}";
        }
        return "{previousChunkConnection=" + safe(note.previousChunkConnection())
                + ", nextChunkConnection=" + safe(note.nextChunkConnection())
                + ", boundaryAdjustmentSuggested=" + note.boundaryAdjustmentSuggested()
                + "}";
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }
}
