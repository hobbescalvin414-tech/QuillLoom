package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

public interface RevisionSelfCheckService {

    RevisionSelfCheckResult check(PostDraftReviewSession session,
                                  PostDraftChunkRecord chunk,
                                  ReviewStrategy strategy,
                                  RevisionDraft draft);
}
