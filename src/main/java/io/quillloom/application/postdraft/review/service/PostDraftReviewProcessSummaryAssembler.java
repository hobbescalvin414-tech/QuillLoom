package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewProblemType;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class PostDraftReviewProcessSummaryAssembler {

    public ReviewProcessSummary assemble(PostDraftReviewSession session,
                                         PostDraftChunkRecord chunk,
                                         ReviewStrategy strategy,
                                         Set<ReviewProblemType> problemTypes,
                                         List<String> evidenceSummaries) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(strategy, "strategy");

        List<String> mergedEvidence = new java.util.ArrayList<>();
        if (evidenceSummaries != null) {
            mergedEvidence.addAll(evidenceSummaries);
        }
        session.keyEvidenceSummaries().stream()
                .map(value -> "key:" + value)
                .forEach(mergedEvidence::add);
        session.conflictingEvidenceSummaries().stream()
                .map(value -> "conflict:" + value)
                .forEach(mergedEvidence::add);
        session.evidenceGaps().stream()
                .map(value -> "gap:" + value)
                .forEach(mergedEvidence::add);
        session.toolTraces().stream()
                .map(trace -> "tool=" + trace.toolName() + ":" + normalize(trace.reason()))
                .forEach(mergedEvidence::add);
        session.diagnostics().localRejectionReasons().stream()
                .map(reason -> "diagnostic:" + reason)
                .forEach(mergedEvidence::add);

        String toolTraceSummary = session.toolTraces().stream()
                .map(trace -> trace.toolName())
                .collect(java.util.stream.Collectors.joining(","));
        StringBuilder processNote = new StringBuilder("chunk=").append(chunk.chunkId())
                .append(", strategy=").append(strategy.name())
                .append(", observationState=").append(resolveObservationState(session))
                .append(", toolTraces=[").append(toolTraceSummary).append("]");
        if (session.operatorNote() != null && !session.operatorNote().isBlank()) {
            processNote.append(", operatorNote=").append(session.operatorNote().trim());
        }
        processNote.append(", toolTraceCount=").append(session.toolTraces().size());
        processNote.append(", keyEvidenceCount=").append(session.keyEvidenceSummaries().size());
        processNote.append(", conflictCount=").append(session.conflictingEvidenceSummaries().size());
        processNote.append(", evidenceGapCount=").append(session.evidenceGaps().size());
        processNote.append(", localRejectionCount=").append(session.diagnostics().localRejectionReasons().size());
        return new ReviewProcessSummary(
                session.projectId(),
                session.focus(),
                strategy,
                problemTypes,
                List.copyOf(mergedEvidence),
                processNote.toString()
        );
    }

    private String resolveObservationState(PostDraftReviewSession session) {
        return session.state().name().toLowerCase(Locale.ROOT);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }
}
