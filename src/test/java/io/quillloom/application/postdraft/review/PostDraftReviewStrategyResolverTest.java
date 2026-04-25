package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.ReviewProblemType;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProblemClassifier;
import io.quillloom.application.postdraft.review.service.PostDraftReviewStrategyResolver;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftReviewStrategyResolverTest {

    private final PostDraftReviewProblemClassifier classifier = new PostDraftReviewProblemClassifier();
    private final PostDraftReviewStrategyResolver resolver = new PostDraftReviewStrategyResolver();

    @Test
    void shouldClassifyUnresolvedDecisionAndResolveHumanReview() {
        PostDraftChunkRecord chunk = ReviewAgentFixtures.chunkWithDecisionNote("chunk-1");

        Set<ReviewProblemType> problems = classifier.classify(chunk);

        assertTrue(problems.contains(ReviewProblemType.UNRESOLVED_DECISION));
        assertEquals(ReviewStrategy.REQUIRE_HUMAN_REVIEW, resolver.resolve(chunk, 0, problems.contains(ReviewProblemType.UNRESOLVED_DECISION)));
    }

    @Test
    void shouldClassifyTransitionContinuitySignal() {
        PostDraftChunkRecord chunk = ReviewAgentFixtures.chunkWithTransitionNote("chunk-2");

        Set<ReviewProblemType> problems = classifier.classify(chunk);

        assertTrue(problems.contains(ReviewProblemType.TRANSITION_CONTINUITY));
    }

    @Test
    void shouldResolveRetranslateWhenEvidenceIsSufficientAndTranslationIsTooShort() {
        PostDraftChunkRecord chunk = ReviewAgentFixtures.chunkWithTranslation("chunk-3", "short");

        assertEquals(ReviewStrategy.RETRANSLATE, resolver.resolve(chunk, 2, false));
    }

    @Test
    void shouldResolveLightEditWhenNoEscalationRuleMatches() {
        PostDraftChunkRecord chunk = ReviewAgentFixtures.chunkWithTranslation("chunk-4", "这是一个已经足够完整的译文");

        assertEquals(ReviewStrategy.LIGHT_EDIT, resolver.resolve(chunk, 1, false));
    }

    @Test
    void shouldRejectNullTranslatedTextAsBoundaryError() {
        PostDraftChunkRecord chunk = new PostDraftChunkRecord(
                "chunk-5",
                1,
                "block-1",
                "source text",
                null,
                "commentary",
                java.util.List.of(),
                java.util.Map.of(),
                java.util.List.of(),
                null
        );

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(chunk, 2, false));
    }
}
