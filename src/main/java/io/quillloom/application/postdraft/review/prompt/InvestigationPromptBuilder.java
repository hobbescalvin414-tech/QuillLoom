package io.quillloom.application.postdraft.review.prompt;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewToolDefinition;
import io.quillloom.application.postdraft.review.service.ReviewWorkingSetCanonicalView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class InvestigationPromptBuilder {

    public record PromptProjectState(
            int pendingChunkCount,
            int completedChunkCount,
            boolean currentFocusChunkStillPending
    ) {
    }

    public String build(PostDraftReviewSession session,
                        List<ReviewToolDefinition> availableTools,
                        List<String> evidenceSummaries) {
        return build(session, availableTools, evidenceSummaries, new PromptProjectState(-1, -1, true));
    }

    public String build(PostDraftReviewSession session,
                        List<ReviewToolDefinition> availableTools,
                        List<String> evidenceSummaries,
                        PromptProjectState projectState) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(availableTools, "availableTools");
        Objects.requireNonNull(projectState, "projectState");
        List<String> safeEvidence = evidenceSummaries == null ? List.of() : List.copyOf(evidenceSummaries);
        String anchorChunkId = session.focus().chunkId();

        return """
                [Current Facts]
                These are the objective facts for the current round. You may make the next-step decision only from these facts and the working-set text context.
                - current focus and anchor chunk: %s
                - chunk set in the current working set: %s
                - adjacent-read status: boundaryLeftChunkId=%s, boundaryRightChunkId=%s, anchorOnlyView=%s, hasPreviousRead=%s, hasNextRead=%s, adjacentReadCount=%s
                - pending / completed / current-focus status: pendingChunkCount=%s, completedChunkCount=%s, currentFocusChunkStillPending=%s
                - existing signals related to revision / self-check / completion: strategy=%s, waitingForHumanReview=%s, recentLocalFailures=%s
                - projectId: %s
                - observationState: %s
                - operatorNote: %s

                [Decision Gate Summary]
                Identify the current review dimension first, then decide the next step by that dimension's gate template.
                Use the gate template that matches the current review dimension. Do not let Java-side heuristics pre-decide the dimension for you.
                If pendingChunkCount=0 and the project is ready, prefer complete_project.
                Allow request_human_review only when local tools cannot close a real semantic issue.
                %s

                [Working Set Text Context]
                This is the full text context of the current working set. Base semantic judgments primarily on sourceText and translatedText here. Do not use summary memory as a substitute for text evidence.
                %s

                [State Memory]
                This is the summary-style state memory available in the current round. Use it only to avoid repeated investigation and to understand current gaps and recent failures:
                [Evidence Summaries]
                %s
                [Key Evidence Summaries]
                %s
                [Conflicting Evidence Summaries]
                %s
                [Evidence Gaps]
                %s
                [Recent Transcript]
                %s
                [Recent Local Failures]
                %s
                These items are not a substitute for sourceText. If semantic judgment still needs text evidence, return to the working-set text context.

                [Output Reminder]
                Output only the next tool decision. First make sure the tool choice and argument structure are valid. Then make sure the decision follows the current round's gates.
                Human-visible summary fields such as reason / questionForHuman should follow the current translation target language by default. 当前项目默认用中文。Keep sourceText 原文引用、术语原文、tool 名称、JSON 键名 as-is when needed.
                Return exactly one valid JSON object. The selected tool's arguments must already be valid in one shot. Example: {"toolName": "read_confirmed_terms", "arguments": {"sourceTerms": ["<source-term>"]}, "reason": "need project-level confirmed-term lookup"}
                """.formatted(
                anchorChunkId,
                ReviewWorkingSetCanonicalView.chunkIds(session),
                boundaryLeftChunkId(session),
                boundaryRightChunkId(session),
                anchorOnlyView(session),
                hasPreviousRead(session),
                hasNextRead(session),
                adjacentReadCount(session),
                renderProjectCount(projectState.pendingChunkCount()),
                renderProjectCount(projectState.completedChunkCount()),
                projectState.currentFocusChunkStillPending(),
                session.strategy(),
                session.waitingForHumanReview(),
                summarizeList(session.diagnostics().localRejectionReasons()),
                session.projectId(),
                session.state().name(),
                normalizeText(session.operatorNote()),
                renderDecisionGateTemplates(),
                renderWorkingSetContext(session),
                renderList(safeEvidence),
                renderList(session.keyEvidenceSummaries()),
                renderList(session.conflictingEvidenceSummaries()),
                renderList(session.evidenceGaps()),
                renderRecentTranscript(session),
                renderLocalFailures(session)
        );
    }

    private static String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        return text;
    }

    private static String renderList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "- (none)";
        }
        return items.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
    }

    private static String summarizeList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "(none)";
        }
        return items.toString();
    }

    private static String renderRecentTranscript(PostDraftReviewSession session) {
        List<String> entries = session.transcriptStore().replay();
        if (entries.isEmpty()) {
            return "- (none)";
        }
        int fromIndex = Math.max(0, entries.size() - 8);
        return renderList(entries.subList(fromIndex, entries.size()));
    }

    private static String renderLocalFailures(PostDraftReviewSession session) {
        return renderList(session.diagnostics().localRejectionReasons());
    }

    private static String renderWorkingSetContext(PostDraftReviewSession session) {
        List<ReviewContextChunkSnapshot> snapshots = ReviewWorkingSetCanonicalView.snapshots(session);
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

    private static String boundaryLeftChunkId(PostDraftReviewSession session) {
        return session.boundaryWindow().leftEdgeChunkId().orElse("-");
    }

    private static String boundaryRightChunkId(PostDraftReviewSession session) {
        return session.boundaryWindow().rightEdgeChunkId().orElse("-");
    }

    private static boolean anchorOnlyView(PostDraftReviewSession session) {
        String left = session.boundaryWindow().leftEdgeChunkId().orElse(null);
        String right = session.boundaryWindow().rightEdgeChunkId().orElse(null);
        if (left == null || right == null) {
            return true;
        }
        return session.focus().chunkId().equals(left)
                && session.focus().chunkId().equals(right);
    }

    private static boolean hasPreviousRead(PostDraftReviewSession session) {
        return session.boundaryWindow().leftEdgeChunkId()
                .filter(chunkId -> !session.focus().chunkId().equals(chunkId))
                .isPresent();
    }

    private static boolean hasNextRead(PostDraftReviewSession session) {
        return session.boundaryWindow().rightEdgeChunkId()
                .filter(chunkId -> !session.focus().chunkId().equals(chunkId))
                .isPresent();
    }

    private static int adjacentReadCount(PostDraftReviewSession session) {
        int count = 0;
        if (hasPreviousRead(session)) {
            count++;
        }
        if (hasNextRead(session)) {
            count++;
        }
        return count;
    }

    private static String renderProjectCount(int value) {
        return value < 0 ? "-" : String.valueOf(value);
    }

    private static String renderDecisionGateTemplates() {
        return """
                continuity gate:
                If the judgment depends on unread adjacent chunks, read the necessary chunks first.
                Before the required adjacent reading is complete, do not evaluate_focus and do not complete_working_set.

                term gate:
                Not looked up yet: call read_confirmed_terms first.
                Already looked up and already compared: do not look it up again.
                No stable pair yet: do not record_confirmed_terms.
                Confirmed translation conflict: do not KEEP or complete_working_set; move to evaluation or revision.

                quality gate:
                This dimension handles translation quality issues that can be judged directly from the current chunk's sourceText and translatedText, such as omission, mistranslation, semantic drift, obvious awkwardness, register mismatch, or clearly wrong wording.
                If the judgment still depends on adjacent carry-over, referents, speaker identity, context logic, or time/space relations, do not conclude early on the quality path. Read the necessary context first.
                Before that context dependency is removed, do not KEEP from the current chunk alone and do not complete_working_set.
                If direct comparison is already sufficient to confirm that no quality problem exists, you may enter evaluate_focus and support KEEP.

                completion gate:
                Completion may become a candidate next step only when a readiness signal is present and there are no unresolved gaps, local failures, or high-priority issues.
                If the project is pending-empty and project-ready, prefer complete_project.
                """;
    }
}
