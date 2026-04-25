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
                [Current State]
                - projectId: %s
                - focus: %s
                - strategy: %s
                - sourceText: %s
                - currentTranslatedText: %s
                - confirmedTermUpdates: %s

                [Working Set Context]
                %s

                [Draft]
                - formalTranslation: %s
                - revisionMode: %s
                - keyRationales:
                %s
                - residualRisks:
                %s

                [Previous Self-Check Findings]
                %s

                [Required Checks]
                1. Verify that formalTranslation is the complete final translation for the current chunk, not a partial fragment or diff.
                2. Verify that formalTranslation follows the confirmed terms present in this round's evidence.
                3. If currentTranslatedText conflicts with confirmed terms, verify that the draft has fixed that conflict.
                4. If previous findings are not empty, verify that the draft addresses them one by one.
                5. If any check fails, you must return passed=false and explain the reason in findings.

                [Output Contract]
                Return exactly one JSON object and do not add explanatory text outside JSON:
                {
                  "passed": true,
                  "stopReason": "",
                  "findings": ["issues found by self-check"]
                }
                """.formatted(
                session.projectId(),
                session.focus(),
                strategy,
                normalizeText(chunk.sourceText()),
                normalizeText(chunk.effectiveTranslatedText()),
                chunk.confirmedTermUpdates(),
                renderWorkingSetContext(session),
                draft.formalTranslation(),
                draft.revisionMode(),
                renderList(draft.keyRationales()),
                renderList(draft.residualRisks()),
                renderList(previousFindings)
        );
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
