package io.quillloom.domain.knowledge;

import java.util.List;

/**
 * 候选译名或术语条目，不代表已确认决策。
 */
public record CandidateTerm(
        String sourceTerm,
        List<String> candidateTranslations,
        String category,
        String rationale
) {
}