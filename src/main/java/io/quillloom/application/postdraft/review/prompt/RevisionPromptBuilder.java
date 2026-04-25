package io.quillloom.application.postdraft.review.prompt;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class RevisionPromptBuilder {

    public String build(PostDraftReviewSession session,
                        PostDraftChunkRecord chunk,
                        ReviewStrategy targetStrategy,
                        List<String> keyRationales,
                        List<String> residualRisks) {
        return buildInternal(session, chunk, targetStrategy, keyRationales, residualRisks, "");
    }

    public String buildRetryPrompt(PostDraftReviewSession session,
                                   PostDraftChunkRecord chunk,
                                   ReviewStrategy targetStrategy,
                                   List<String> keyRationales,
                                   List<String> residualRisks,
                                   String previousFailure) {
        return buildInternal(session, chunk, targetStrategy, keyRationales, residualRisks, previousFailure);
    }

    private String buildInternal(PostDraftReviewSession session,
                                 PostDraftChunkRecord chunk,
                                 ReviewStrategy targetStrategy,
                                 List<String> keyRationales,
                                 List<String> residualRisks,
                                 String previousFailure) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(targetStrategy, "targetStrategy");
        List<String> safeRationales = keyRationales == null ? List.of() : List.copyOf(keyRationales);
        List<String> safeRisks = residualRisks == null ? List.of() : List.copyOf(residualRisks);
        String failureSection = previousFailure == null || previousFailure.isBlank()
                ? "- (none)"
                : "- " + previousFailure.trim();

        return """
                [Current Facts]
                - projectId: %s
                - focus: %s
                - observationState: %s
                - currentStrategy: %s
                - targetStrategy: %s
                - operatorNote: %s

                [Current Chunk]
                - chunkId: %s
                - sourceText: %s
                - currentTranslatedText: %s
                - confirmedTermUpdates: %s

                [Working Set Context]
                %s

                [Key Rationale]
                %s

                [Residual Risks]
                %s

                [Previous Output Failure]
                %s

                [Output Contract]
                Return one JSON object only:
                {
                  "formalTranslation": "final translated text for this chunk, must be non-empty",
                  "revisionMode": "LIGHT_EDIT",
                  "keyRationales": ["key rationale"],
                  "residualRisks": ["residual risk"]
                }

                [Rules]
                1. formalTranslation must be a non-empty string. It must not be blank or null.
                2. revisionMode must exactly match targetStrategy.
                3. Do not omit any field.
                4. formalTranslation must be the full translated chunk, not a partial diff.
                5. If key rationale indicates a confirmed-term conflict, formalTranslation must resolve it.
                """.formatted(
                session.projectId(),
                session.focus(),
                session.state().name(),
                session.strategy(),
                targetStrategy,
                normalizeText(session.operatorNote()),
                chunk.chunkId(),
                normalizeText(chunk.sourceText()),
                normalizeText(chunk.effectiveTranslatedText()),
                chunk.confirmedTermUpdates(),
                renderWorkingSetContext(session),
                renderList(safeRationales),
                renderList(safeRisks),
                failureSection
        );
    }

    private static String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return "(none)";
        }
        return text;
    }

    private static String renderList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "- (none)";
        }
        return items.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
    }

    private static String renderWorkingSetContext(PostDraftReviewSession session) {
        List<ReviewContextChunkSnapshot> snapshots = session.workingSetContext().snapshots();
        if (snapshots.isEmpty()) {
            return "- (none)";
        }
        ArrayList<String> rendered = new ArrayList<>();
        for (ReviewContextChunkSnapshot snapshot : snapshots) {
            rendered.add("chunkId=" + snapshot.chunkId()
                    + ", sequence=" + snapshot.sequence()
                    + ", anchor=" + snapshot.anchor()
                    + ", sourceText=" + normalizeText(snapshot.sourceText())
                    + ", translatedText=" + normalizeText(snapshot.translatedText())
                    + ", confirmedTermUpdates=" + snapshot.confirmedTermUpdates()
                    + ", transitionNote=" + normalizeText(snapshot.transitionNote()));
        }
        return renderList(rendered);
    }
}
