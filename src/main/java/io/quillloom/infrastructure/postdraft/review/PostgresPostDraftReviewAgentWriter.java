package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentWriter;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PostgresPostDraftReviewAgentWriter implements PostDraftReviewAgentWriter {

    private final PostDraftReviewPackageRepository reviewPackageRepository;

    public PostgresPostDraftReviewAgentWriter(PostDraftReviewPackageRepository reviewPackageRepository) {
        this.reviewPackageRepository = Objects.requireNonNull(reviewPackageRepository, "reviewPackageRepository");
    }

    @Override
    public PostDraftReviewAgentResult writeCompleted(String finalTranslatedText, ReviewProcessSummary processSummary) {
        throw new UnsupportedOperationException("PostgresPostDraftReviewAgentWriter is for project-level writeback only");
    }

    @Override
    public PostDraftReviewAgentResult writeHumanRequired(HumanReviewRequest request) {
        throw new UnsupportedOperationException("PostgresPostDraftReviewAgentWriter does not publish human-required responses");
    }

    @Override
    public void writeCompletedChunks(String projectId,
                                     List<ProjectChunkReviewOutcome> outcomes) {
        String normalizedProjectId = requireText(projectId, "projectId");
        List<ProjectChunkReviewOutcome> safeOutcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        if (safeOutcomes.isEmpty()) {
            return;
        }

        PostDraftReviewPackage reviewPackage = loadRequiredPackage(normalizedProjectId);
        Map<String, String> translationByChunkId = new LinkedHashMap<>();
        for (ProjectChunkReviewOutcome outcome : safeOutcomes) {
            translationByChunkId.put(outcome.chunkId(), outcome.finalTranslation());
        }

        // 当前依赖“单 agent / 单 projectId 串行运行”前提，因此这里采用 read-modify-write。
        ArrayList<PostDraftChunkRecord> updatedChunks = new ArrayList<>();
        for (PostDraftChunkRecord chunk : reviewPackage.chunks()) {
            String nextTranslation = translationByChunkId.get(chunk.chunkId());
            if (nextTranslation == null) {
                updatedChunks.add(chunk);
                continue;
            }
            String normalizedRevision = requireRevisionText(nextTranslation, chunk.chunkId());
            updatedChunks.add(new PostDraftChunkRecord(
                    chunk.chunkId(),
                    chunk.sequence(),
                    chunk.blockId(),
                    chunk.sourceText(),
                    chunk.translatedText(),
                    normalizedRevision,
                    chunk.translatorCommentary(),
                    chunk.decisionNotes(),
                    chunk.confirmedTermUpdates(),
                    chunk.candidateUpdates(),
                    chunk.transitionNote()
            ));
        }

        reviewPackageRepository.save(copyPackage(reviewPackage, List.copyOf(updatedChunks), reviewPackage.mergedDraftText()));
    }

    @Override
    public void writeMergedDraftText(String projectId,
                                     String mergedDraftText) {
        String normalizedProjectId = requireText(projectId, "projectId");
        String normalizedText = mergedDraftText == null ? "" : mergedDraftText.trim();
        PostDraftReviewPackage reviewPackage = loadRequiredPackage(normalizedProjectId);

        // 当前依赖“单 agent / 单 projectId 串行运行”前提，因此这里采用 read-modify-write。
        reviewPackageRepository.save(copyPackage(reviewPackage, reviewPackage.chunks(), normalizedText));
    }

    @Override
    public void writeMergedDraftFromProjectChunks(String projectId) {
        String normalizedProjectId = requireText(projectId, "projectId");
        PostDraftReviewPackage reviewPackage = loadRequiredPackage(normalizedProjectId);
        String mergedDraftText = reviewPackage.chunks().stream()
                .sorted(Comparator.comparingInt(PostDraftChunkRecord::sequence).thenComparing(PostDraftChunkRecord::chunkId))
                .map(PostDraftChunkRecord::effectiveTranslatedText)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");

        reviewPackageRepository.save(copyPackage(reviewPackage, reviewPackage.chunks(), mergedDraftText));
    }

    @Override
    public void resetProjectRevisions(String projectId) {
        String normalizedProjectId = requireText(projectId, "projectId");
        PostDraftReviewPackage reviewPackage = loadRequiredPackage(normalizedProjectId);

        ArrayList<PostDraftChunkRecord> resetChunks = new ArrayList<>();
        for (PostDraftChunkRecord chunk : reviewPackage.chunks()) {
            resetChunks.add(new PostDraftChunkRecord(
                    chunk.chunkId(),
                    chunk.sequence(),
                    chunk.blockId(),
                    chunk.sourceText(),
                    chunk.translatedText(),
                    null,
                    chunk.translatorCommentary(),
                    chunk.decisionNotes(),
                    chunk.confirmedTermUpdates(),
                    chunk.candidateUpdates(),
                    chunk.transitionNote()
            ));
        }

        reviewPackageRepository.save(copyPackage(reviewPackage, List.copyOf(resetChunks), ""));
    }

    private PostDraftReviewPackage loadRequiredPackage(String projectId) {
        return reviewPackageRepository.load(projectId)
                .orElseThrow(() -> new IllegalStateException("Post-draft review package not found for projectId=" + projectId));
    }

    private PostDraftReviewPackage copyPackage(PostDraftReviewPackage original,
                                               List<PostDraftChunkRecord> chunks,
                                               String mergedDraftText) {
        return new PostDraftReviewPackage(
                original.projectId(),
                original.packageVersion(),
                original.sourceLanguage(),
                original.targetLanguage(),
                original.sourceDocumentDigest(),
                original.createdAt(),
                chunks,
                original.blockIndexes(),
                original.termState(),
                original.glossarySnapshot(),
                original.aliasSnapshot(),
                mergedDraftText
        );
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String requireRevisionText(String value, String chunkId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("revisedTranslatedText must not be blank for chunkId=" + chunkId);
        }
        return value.trim();
    }
}
