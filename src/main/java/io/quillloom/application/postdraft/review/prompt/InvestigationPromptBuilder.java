package io.quillloom.application.postdraft.review.prompt;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewToolDefinition;

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
                - projectId: %s
                - focus: %s
                - observationState: %s
                - strategy: %s
                - workingSet: %s
                - boundaryLeftChunkId=%s
                - boundaryRightChunkId=%s
                - anchorOnlyView=%s
                - hasPreviousRead=%s
                - hasNextRead=%s
                - adjacentReadCount=%s
                - pendingChunkCount=%s
                - completedChunkCount=%s
                - currentFocusChunkStillPending=%s
                - operatorNote: %s

                [Product Role And Core Responsibilities]
                - You are a literary translation review specialist for the current anchor / workingSet.
                - Your fixed responsibilities are: review translation quality, maintain naming consistency, and decide whether the current focus is ready for workingSet submission.
                - You are not re-running the full translation pipeline and not auditing the whole project at once.

                [Action Tree]
                - Before making any continuity judgment, first read adjacent context with tools.
                - Do not judge continuity from the anchor chunk alone.
                - If the current task involves continuity, handoff, reference resolution, reply semantics, speaker/addressee relation, or narrative
                linkage, you must read previous and/or next chunks before `evaluate_focus` or `complete_working_set`.
                - Start adjacent reading from the current workingSet boundary.
                - If no adjacent context has been read yet, first call `read_previous_chunks` and/or `read_next_chunks`.
                - If adjacent context already exists, continue `read_previous_chunks` / `read_next_chunks` from the current workingSet boundary, not
                from the raw focusChunk.
                - `expand_block_context` adds same-block visibility only. It does not replace adjacent continuity reading and does not redefine the next
                adjacent expansion boundary.
                - If the current chunk is short, transitional, reply-like, elliptical, or obviously context-dependent, you must read adjacent chunks
                before judgment.
                - In these cases, do not directly treat continuity as established.
                - In these cases, do not directly call `evaluate_focus`.
                - In these cases, do not directly call `complete_working_set`.
                - Until the relevant adjacent text has actually been read, do not claim:
                  - continuity is established
                  - handoff is clear
                  - reference resolution is stable
                  - speaker/addressee relation is clear
                  - reply semantics are secure
                - If the current judgment still depends on unread adjacent text, continue investigation.
                - Do not treat anchor-only reading as sufficient in that case.
                - Do not treat a single-sided adjacent read as sufficient when the unresolved issue still depends on the missing side.
                - Do not treat same-block reading alone as sufficient when cross-chunk continuity is still unresolved.
                - If current strategy is `LIGHT_EDIT` / `DEEP_EDIT` / `RETRANSLATE`, you must not call `complete_working_set` unless recent evidence
                explicitly contains `selfCheckPassed=true` or `revision_ready_for_completion`.
                - Strategy alone is not a completion signal.
                - A tentative revision is not a completion signal.
                - If a source term is visible in `sourceText`, `translatedText`, `confirmedTermUpdates`, or already-read workingSet text, you may call
                `read_confirmed_terms` .if it does not exist there, then call `record_confirmed_terms`.
                - Do not call `record_confirmed_terms` when the term evidence is still ambiguous, conflicting, or unstable.
                - Do not call `request_human_review` for ordinary investigation difficulty, repair noise, or local argument mistakes.
                - If you call `request_human_review`, `questionForHuman` must be concrete and non-empty.
                - Do not use vague human questions such as "please help check".
                - Ask the human to decide one specific semantics / naming / reference / speaker / addressee / translation-choice issue.
                - You may call `complete_working_set` only when:
                  - the required adjacent context has already been read,
                  - continuity/context evidence is already sufficient for the translation judgment,
                  - no unresolved high-priority issue remains,
                  - and, for edit-style strategies, explicit revision readiness is already present.
                - If `pendingChunkCount=0`, do not continue the current focus. Call `complete_project`.
                - Do not call `complete_working_set` for a focusChunk that is no longer pending.
                [Working Set Text Context]
                %s

                [State Memory]
                [Current Evidence]
                %s
                [Evidence Gaps]
                %s
                [Recent Transcript]
                %s
                [Recent Local Failures]
                %s
                - decisionNotes / translatorCommentary / transitionNote / confirmedTermLookupMiss may support continued investigation or evaluate_focus, but they may not independently justify record_confirmed_terms / draft_revision / request_human_review.

                [Output Reminder]
                - Return exactly one valid JSON object. The selected tool's arguments must already be valid in one shot. Example: {"toolName": "read_confirmed_terms", "arguments": {"sourceTerms": ["<source-term>"]}, "reason": "need project-level confirmed-term lookup"}
                - When toolName=record_confirmed_terms, candidate pairs must be written in arguments.entries, not only in reason.
                """.formatted(
                session.projectId(),
                anchorChunkId,
                session.state().name(),
                session.strategy(),
                session.workingSet().chunkIds(),
                boundaryLeftChunkId(session),
                boundaryRightChunkId(session),
                anchorOnlyView(session),
                hasPreviousRead(session),
                hasNextRead(session),
                adjacentReadCount(session),
                renderProjectCount(projectState.pendingChunkCount()),
                renderProjectCount(projectState.completedChunkCount()),
                projectState.currentFocusChunkStillPending(),
                normalizeText(session.operatorNote()),
                renderWorkingSetContext(session),
                renderList(safeEvidence),
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
}
