package io.quillloom.application.postdraft.review.prompt;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
        String retryNote = previousFailure == null || previousFailure.isBlank()
                ? ""
                : "\nPrevious output failure to avoid repeating: " + previousFailure.trim();

        return """
                [Revision Target]
                The revision target for this round is:
                - current revision direction: %s
                - issues that must be fixed in this round: %s
                - confirmed-term constraints for this round: %s
                - boundary that must not be expanded: %s
                - residual risks that still require attention: %s

                You must fix these issues first. Do not turn this revision into unrelated polishing or free rewriting.
                If the revision target includes a confirmed-term conflict or a naming inconsistency in names, titles, places, or proper nouns, fix that naming issue first and make formalTranslation consistent with the confirmed evidence.

                [Revision Contract]
                You are only producing a revised translation draft under the given revision direction. Do not choose tools and do not submit completion.
                Use the current chunk's sourceText, currentTranslatedText, working-set context, and the current Revision Target to produce the complete formal translation draft for this chunk for self-check.
                If some content is unrelated to the current Revision Target and does not affect the revision goal, keep it stable and do not rewrite it casually.
                Do not expand scope. Do not pretend unresolved evidence is already closed.
                Do not fabricate certainty when a key semantic prerequisite is still unresolved.
                Produce a complete translation only within the available evidence.
                Do not use placeholders, blanks, vague wording, or explanatory text to hide unresolved risk.
                The output must be the complete formal translation of the current chunk. Do not output a diff, a partial fragment, explanatory text, or an unfinished draft.
                The draft must solve the confirmed issues of this round first, not perform generic polishing.
                Unrelated meaning, information, and structure should remain stable unless changing them is necessary to solve the current issue.
                If there are confirmed-term constraints in this round, formalTranslation must satisfy them.
                If revisionMode is not RETRANSLATE, do not expand the task into whole-sentence free rewriting.%s

                [Current Chunk]
                - chunkId: %s
                - sourceText: %s
                - currentTranslatedText: %s
                - confirmedTermUpdates: %s

                [Working Set Context]
                %s

                [Output Contract]
                Output exactly one JSON object. The fields must be:
                - formalTranslation
                - revisionMode
                - keyRationales
                - residualRisks

                Requirements:
                - formalTranslation must be the complete formal translation of the current chunk
                - revisionMode must be one of: KEEP / LIGHT_EDIT / DEEP_EDIT / RETRANSLATE
                - keyRationales must be an array
                - residualRisks must be an array
                """.formatted(
                targetStrategy.name(),
                summarizeMustFixItems(safeRationales),
                summarizeConfirmedTermConstraints(session, chunk, safeRationales),
                summarizeBoundary(targetStrategy, safeRationales),
                summarizeResidualRisks(safeRisks),
                retryNote,
                chunk.chunkId(),
                normalizeText(chunk.sourceText()),
                normalizeText(chunk.effectiveTranslatedText()),
                chunk.confirmedTermUpdates(),
                renderWorkingSetContext(session)
        );
    }

    private static String summarizeMustFixItems(List<String> keyRationales) {
        if (keyRationales.isEmpty()) {
            return "(none)";
        }
        return String.join("; ", keyRationales);
    }

    private static String summarizeConfirmedTermConstraints(PostDraftReviewSession session,
                                                            PostDraftChunkRecord chunk,
                                                            List<String> keyRationales) {
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        if (!chunk.confirmedTermUpdates().isEmpty()) {
            constraints.add(chunk.confirmedTermUpdates().toString());
        }
        for (ReviewContextChunkSnapshot snapshot : session.workingSetContext().snapshots()) {
            if (!snapshot.confirmedTermUpdates().isEmpty()) {
                constraints.add(snapshot.confirmedTermUpdates().toString());
            }
        }
        for (String rationale : keyRationales) {
            String normalized = rationale.toLowerCase();
            if (normalized.contains("term") || normalized.contains("naming")) {
                constraints.add(rationale);
            }
        }
        if (constraints.isEmpty()) {
            return "(none)";
        }
        return String.join("; ", constraints);
    }

    private static String summarizeBoundary(ReviewStrategy targetStrategy, List<String> keyRationales) {
        String base = "Keep unrelated meaning, information, and structure stable within the current chunk and working set.";
        if (targetStrategy == ReviewStrategy.RETRANSLATE) {
            return base + " RETRANSLATE may rewrite the full chunk, but must still stay inside the current chunk boundary.";
        }
        if (keyRationales.isEmpty()) {
            return base + " Do not expand into free rewriting.";
        }
        return base + " Fix only issues justified by: " + String.join("; ", keyRationales);
    }

    private static String summarizeResidualRisks(List<String> residualRisks) {
        if (residualRisks.isEmpty()) {
            return "(none)";
        }
        return String.join("; ", residualRisks);
    }

    private static String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return "(none)";
        }
        return text;
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
        return rendered.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
    }
}
