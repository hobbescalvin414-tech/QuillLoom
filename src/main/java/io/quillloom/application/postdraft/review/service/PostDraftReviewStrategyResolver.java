package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

public class PostDraftReviewStrategyResolver {

    public ReviewStrategy resolve(PostDraftChunkRecord chunk, int evidenceCount, boolean unresolvedDecisionExists) {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk must not be null");
        }
        if (unresolvedDecisionExists) {
            return ReviewStrategy.REQUIRE_HUMAN_REVIEW;
        }
        if (evidenceCount >= 2 && translatedTextLength(chunk) < 40) {
            return ReviewStrategy.RETRANSLATE;
        }
        return ReviewStrategy.LIGHT_EDIT;
    }

    private static int translatedTextLength(PostDraftChunkRecord chunk) {
        String effectiveTranslation = chunk.effectiveTranslatedText();
        if (effectiveTranslation == null) {
            throw new IllegalArgumentException("chunk.effectiveTranslatedText must not be null");
        }
        return effectiveTranslation.length();
    }
}
