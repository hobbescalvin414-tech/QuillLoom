package io.quillloom.domain.translation;

/**
 * 记录本 chunk 产生的候选译名或候选表达更新。
 */
public record TranslationCandidateUpdate(
        String sourceTerm,
        String candidateTranslation,
        String rationale,
        boolean requiresReview
) {
}