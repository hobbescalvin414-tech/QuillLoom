package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolTrace;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class WorkingSetCompletionHandler {

    private final PostDraftReviewAgentReader reader;
    private final PostDraftReviewProcessSummaryAssembler summaryAssembler;

    public WorkingSetCompletionHandler(PostDraftReviewAgentReader reader,
                                       PostDraftReviewProcessSummaryAssembler summaryAssembler) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.summaryAssembler = Objects.requireNonNull(summaryAssembler, "summaryAssembler");
    }

    public List<ProjectChunkReviewOutcome> complete(ProjectReviewRuntimeSession runtime,
                                                    List<String> chunkIds,
                                                    Map<String, String> explicitFinalTranslations) {
        Objects.requireNonNull(runtime, "runtime");
        PostDraftReviewSession session = runtime.currentFocusSession()
                .orElseThrow(() -> new IllegalStateException("complete working set requires currentFocusSession"));
        List<String> safeChunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
        if (safeChunkIds.isEmpty()) {
            throw new IllegalArgumentException("chunkIds must not be empty");
        }
        validateConfirmedChunkIds(runtime, session, safeChunkIds);
        Map<String, String> finalTranslations = explicitFinalTranslations == null
                ? Map.of()
                : new LinkedHashMap<>(explicitFinalTranslations);

        ArrayList<ProjectChunkReviewOutcome> outcomes = new ArrayList<>();
        for (String chunkId : safeChunkIds) {
            PostDraftChunkRecord chunk = reader.loadChunkById(runtime.projectId(), chunkId)
                    .orElseThrow(() -> new IllegalStateException("Chunk not found for completion: " + chunkId));
            ReviewStrategy strategy = resolveStrategy(session, chunkId);
            String finalTranslation = resolveFinalTranslation(session, chunk, chunkId, finalTranslations);
            ReviewProcessSummary processSummary = summaryAssembler.assemble(
                    session,
                    chunk,
                    strategy,
                    session.problemTypes(),
                    session.evidenceSummaries()
            );
            outcomes.add(new ProjectChunkReviewOutcome(
                    chunkId,
                    finalTranslation,
                    strategy,
                    new ReviewProcessSummary(
                            processSummary.projectId(),
                            ReviewFocus.forChunk(chunkId),
                            processSummary.strategy(),
                            processSummary.problemTypes(),
                            processSummary.evidenceSummaries(),
                            processSummary.processNote() + ", workingSetCompleted=true"
                    )
            ));
        }
        return List.copyOf(outcomes);
    }

    private ReviewStrategy resolveStrategy(PostDraftReviewSession session, String chunkId) {
        if (session.workingSet().chunkIds().contains(chunkId)) {
            return session.strategy();
        }
        return ReviewStrategy.KEEP;
    }

    private String resolveFinalTranslation(PostDraftReviewSession session,
                                           PostDraftChunkRecord chunk,
                                           String chunkId,
                                           Map<String, String> explicitFinalTranslations) {
        String explicit = explicitFinalTranslations.get(chunkId);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        if (chunkId.equals(session.focus().chunkId())) {
            String fromTrace = resolveDraftTranslation(session.toolTraces());
            if (fromTrace != null && !fromTrace.isBlank()) {
                return fromTrace.trim();
            }
        }
        String effectiveTranslation = chunk.effectiveTranslatedText();
        if (effectiveTranslation == null || effectiveTranslation.isBlank()) {
            throw new IllegalStateException("Completed working set requires non-empty translatedText for chunk=" + chunkId);
        }
        return effectiveTranslation.trim();
    }

    private String resolveDraftTranslation(List<ReviewToolTrace> toolTraces) {
        for (int index = toolTraces.size() - 1; index >= 0; index--) {
            ReviewToolTrace trace = toolTraces.get(index);
            if (!"draft_revision".equals(trace.toolName())) {
                continue;
            }
            for (String note : trace.notes()) {
                if (note != null && note.startsWith("finalTranslation=")) {
                    return note.substring("finalTranslation=".length());
                }
            }
        }
        return null;
    }

    private void validateConfirmedChunkIds(ProjectReviewRuntimeSession runtime,
                                           PostDraftReviewSession session,
                                           List<String> chunkIds) {
        String anchorChunkId = session.focus().chunkId();
        if (!chunkIds.contains(anchorChunkId)) {
            throw new IllegalArgumentException("complete_working_set chunkIds must include anchorChunkId=" + anchorChunkId);
        }

        List<String> workingSetChunkIds = session.workingSet().chunkIds();
        for (String chunkId : chunkIds) {
            if (!workingSetChunkIds.contains(chunkId)) {
                throw new IllegalArgumentException(
                        "complete_working_set chunkIds must stay within currentWorkingSet="
                                + workingSetChunkIds
                                + ", offendingChunkId="
                                + chunkId
                );
            }
            if (!runtime.pendingChunkIds().contains(chunkId)) {
                throw new IllegalArgumentException(
                        "complete_working_set chunkIds must still be pending, offendingChunkId=" + chunkId
                );
            }
            if (!anchorChunkId.equals(chunkId)) {
                throw new IllegalArgumentException(
                        "complete_working_set currently allows only focusChunk="
                                + anchorChunkId
                                + "; offendingChunkId="
                                + chunkId
                );
            }
        }
    }
}
