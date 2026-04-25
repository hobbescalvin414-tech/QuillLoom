package io.quillloom.application.postdraft.review.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PostDraftReviewAgentResult(
        String finalTranslatedText,
        String finalMergedTranslatedText,
        List<ProjectChunkReviewOutcome> completedChunkResults,
        ReviewProcessSummary processSummary,
        Optional<HumanReviewRequest> humanReviewRequest
) {

    public PostDraftReviewAgentResult {
        processSummary = Objects.requireNonNull(processSummary, "processSummary");
        finalTranslatedText = normalize(finalTranslatedText);
        String normalizedMerged = normalize(finalMergedTranslatedText);
        finalMergedTranslatedText = normalizedMerged.isBlank() ? finalTranslatedText : normalizedMerged;
        completedChunkResults = completedChunkResults == null ? List.of() : List.copyOf(completedChunkResults);
        humanReviewRequest = humanReviewRequest == null ? Optional.empty() : humanReviewRequest;
    }

    public PostDraftReviewAgentResult(String finalTranslatedText,
                                      ReviewProcessSummary processSummary,
                                      Optional<HumanReviewRequest> humanReviewRequest) {
        this(finalTranslatedText, finalTranslatedText, List.of(), processSummary, humanReviewRequest);
    }

    public static PostDraftReviewAgentResult forProject(String finalMergedTranslatedText,
                                                        List<ProjectChunkReviewOutcome> completedChunkResults,
                                                        ReviewProcessSummary processSummary,
                                                        Optional<HumanReviewRequest> humanReviewRequest) {
        return new PostDraftReviewAgentResult(
                finalMergedTranslatedText,
                finalMergedTranslatedText,
                completedChunkResults,
                processSummary,
                humanReviewRequest
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
