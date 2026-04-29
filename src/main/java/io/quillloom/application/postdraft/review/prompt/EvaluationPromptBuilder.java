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
                [Evaluation Inputs]
                You will receive Key Evidence, Conflicting Evidence, and Evidence Gaps for the current round.
                They are the direct basis for judging evidenceSufficiency and continueInvestigation. Do not choose a strategy from text impression alone.

                [Evaluation Handoff]
                If this stage concludes that the work should enter revision, the next stage must use the current Revision Target. Do not reinvent the revision task in the revision stage.

                [Evaluation Task]
                You are only evaluating the handling strategy for the current focus. Do not choose the next tool and do not directly advance to revision or completion.
                Based on the current sourceText, translatedText, working-set text context, and collected evidence, decide:
                1. whether the current evidence is sufficient for a strategy decision;
                2. which candidate strategy should be recommended, using the exact strategy name from the candidate strategies;
                3. whether investigation should continue if the evidence is still insufficient.

                [Evaluation Constraints]
                recommendedStrategy must be one of the listed candidate strategies. Copy the chosen strategy name exactly as shown. Do not rename, shorten, or paraphrase it. Do not treat strategy as a completion signal. If the current judgment still depends on unclosed evidence, explicitly return continueInvestigation instead of forcing a strategy conclusion.

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
                Output exactly one JSON object. The fields must be:
                - recommendedStrategy
                - strategyReason
                - evidenceSufficiency
                - continueInvestigation

                Requirements:
                - recommendedStrategy must use an exact value from the given candidate strategies
                - strategyReason must be non-empty
                - evidenceSufficiency must be one of: UNKNOWN / SUFFICIENT / PARTIAL / INSUFFICIENT
                - continueInvestigation must be true or false
                """.formatted(
                renderWorkingSetContext(session),
                renderList(safeKeyEvidence),
                renderList(session.conflictingEvidenceSummaries()),
                renderList(session.evidenceGaps()),
                renderStrategies(safeStrategies)
        );
    }

    private static String renderList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "- (none)";
        }
        return items.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
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

    private static String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return "(none)";
        }
        return text;
    }
}
