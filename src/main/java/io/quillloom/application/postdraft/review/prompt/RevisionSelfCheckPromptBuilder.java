package io.quillloom.application.postdraft.review.prompt;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class RevisionSelfCheckPromptBuilder {

    public String build(PostDraftReviewSession session,
                        PostDraftChunkRecord chunk,
                        ReviewStrategy strategy,
                        RevisionDraft draft) {
        return buildInternal(session, chunk, strategy, draft, List.of());
    }

    public String buildRetryPrompt(PostDraftReviewSession session,
                                   PostDraftChunkRecord chunk,
                                   ReviewStrategy strategy,
                                   RevisionDraft draft,
                                   RevisionSelfCheckResult firstAttempt) {
        Objects.requireNonNull(firstAttempt, "firstAttempt");
        return buildInternal(session, chunk, strategy, draft, firstAttempt.findings());
    }

    private String buildInternal(PostDraftReviewSession session,
                                 PostDraftChunkRecord chunk,
                                 ReviewStrategy strategy,
                                 RevisionDraft draft,
                                 List<String> previousFindings) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(draft, "draft");

        return """
                [Self-Check Objective]
                This self-check must evaluate the draft against the current Revision Target, not as a generic quality review.

                [Revision Target]
                - current revision direction: %s
                - current chunk sourceText: %s
                - current chunk currentTranslatedText: %s
                - confirmed-term constraints for this round: %s
                - key rationales from the current draft: %s
                - residual risks from the current draft: %s

                [Self-Check Task]
                You are only checking whether the revision draft is ready for submission. Do not directly trigger completion.
                Check whether the current revised draft:
                1. has fixed each must-fix item in the current Revision Target;
                2. satisfies the confirmed-term constraints in the current Revision Target;
                3. addresses previous findings one by one if previous findings exist;
                4. has not introduced any new obvious semantic error;
                5. remains consistent with the working-set context;
                6. is ready to be considered for completion.

                [Current Draft]
                - formalTranslation: %s
                - revisionMode: %s

                [Working Set Context]
                %s

                [Previous Findings]
                %s

                [Self-Check Constraints]
                Output only the self-check result. Do not treat it as a completion action.

                [Output Contract]
                Output exactly one JSON object. The fields must be:
                - passed
                - stopReason
                - findings

                Requirements:
                - passed must be true or false
                - stopReason must be a string; it may be empty when the check passes
                - findings must be an array; when the check fails, list the failed items
                """.formatted(
                strategy.name(),
                normalizeText(chunk.sourceText()),
                normalizeText(chunk.effectiveTranslatedText()),
                summarizeConfirmedTermConstraints(session, chunk),
                summarizeList(draft.keyRationales()),
                summarizeList(draft.residualRisks()),
                normalizeText(draft.formalTranslation()),
                draft.revisionMode(),
                renderWorkingSetContext(session),
                renderList(previousFindings)
        );
    }

    private static String summarizeConfirmedTermConstraints(PostDraftReviewSession session,
                                                            PostDraftChunkRecord chunk) {
        List<String> constraints = new ArrayList<>();
        if (!chunk.confirmedTermUpdates().isEmpty()) {
            constraints.add(chunk.confirmedTermUpdates().toString());
        }
        for (ReviewContextChunkSnapshot snapshot : session.workingSetContext().snapshots()) {
            if (!snapshot.confirmedTermUpdates().isEmpty()) {
                constraints.add(snapshot.confirmedTermUpdates().toString());
            }
        }
        return constraints.isEmpty() ? "(none)" : String.join("; ", constraints);
    }

    private static String summarizeList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "(none)";
        }
        return String.join("; ", items);
    }

    private static String renderList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "- (none)";
        }
        return items.stream()
                .map(item -> "- " + item)
                .collect(Collectors.joining("\n"));
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "(none)";
        }
        return value.trim();
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
