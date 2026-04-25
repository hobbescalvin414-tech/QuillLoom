package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.Objects;

public class PostDraftRevisionService {

    private final RevisionDraftProvider draftProvider;
    private final RevisionSelfCheckService selfCheckService;

    public PostDraftRevisionService(RevisionDraftProvider draftProvider,
                                    RevisionSelfCheckService selfCheckService) {
        this.draftProvider = Objects.requireNonNull(draftProvider, "draftProvider");
        this.selfCheckService = Objects.requireNonNull(selfCheckService, "selfCheckService");
    }

    public RevisionDraft generate(PostDraftReviewSession session,
                                  PostDraftChunkRecord chunk,
                                  ReviewStrategy strategy) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(strategy, "strategy");
        if (strategy == ReviewStrategy.KEEP || strategy == ReviewStrategy.REQUIRE_HUMAN_REVIEW) {
            throw new IllegalArgumentException("revision service only supports executable revision strategies");
        }
        return draftProvider.generate(session, chunk, strategy);
    }

    public RevisionSelfCheckResult selfCheck(PostDraftReviewSession session,
                                             PostDraftChunkRecord chunk,
                                             ReviewStrategy strategy,
                                             RevisionDraft draft) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(draft, "draft");
        return selfCheckService.check(session, chunk, strategy, draft);
    }
}
