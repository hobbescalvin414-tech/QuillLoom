package io.quillloom.application.postdraft.review.prompt;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class EvaluationPromptBuilder {

    public String build(PostDraftReviewSession session,
                        Set<ReviewStrategy> targetStrategies,
                        List<String> keyEvidenceSummaries) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(targetStrategies, "targetStrategies");
        List<String> safeKeyEvidence = keyEvidenceSummaries == null ? List.of() : List.copyOf(keyEvidenceSummaries);
        Set<ReviewStrategy> safeStrategies = Set.copyOf(targetStrategies);

        return """
                [Current Facts]
                - projectId: %s
                - focus: %s
                - observationState: %s
                - workingSet: %s
                - problemTypes: %s
                - operatorNote: %s

                [Working Set Text Context]
                %s

                [State Memory]
                [Key Evidence]
                %s
                [Conflicting Evidence]
                %s
                [Evidence Gaps]
                %s

                [Candidate Strategies]
                %s

                [Output Contract]
                Return exactly one JSON object and do not add explanatory text outside JSON:
                {
                  "recommendedStrategy": "LIGHT_EDIT",
                  "strategyReason": "reason for the strategy",
                  "evidenceSufficiency": "SUFFICIENT",
                  "continueInvestigation": false
                }
                recommendedStrategy must be chosen from the candidate strategies listed above.
                evidenceSufficiency must be one of UNKNOWN / SUFFICIENT / PARTIAL / INSUFFICIENT.
                """.formatted(
                session.projectId(),
                session.focus(),
                session.state().name(),
                session.workingSet().chunkIds(),
                renderProblemTypes(session.problemTypes()),
                normalizeText(session.operatorNote()),
                renderWorkingSetContext(session),
                renderList(safeKeyEvidence),
                renderList(session.conflictingEvidenceSummaries()),
                renderList(session.evidenceGaps()),
                renderStrategies(safeStrategies)
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

    private static String renderProblemTypes(Set<?> problemTypes) {
        if (problemTypes == null || problemTypes.isEmpty()) {
            return "[]";
        }
        return problemTypes.stream().map(String::valueOf).sorted().collect(Collectors.joining(", ", "[", "]"));
    }

    private static String renderStrategies(Set<ReviewStrategy> strategies) {
        if (strategies.isEmpty()) {
            return "- (none)";
        }
        return strategies.stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(strategy -> "- " + strategy.name())
                .collect(Collectors.joining("\n"));
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
                    + ", translatorCommentary=" + normalizeText(snapshot.translatorCommentary())
                    + ", decisionNotes=" + snapshot.decisionNotes()
                    + ", confirmedTermUpdates=" + snapshot.confirmedTermUpdates()
                    + ", transitionNote=" + normalizeText(snapshot.transitionNote()));
        }
        return renderList(rendered);
    }
}
