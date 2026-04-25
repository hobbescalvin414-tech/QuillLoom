package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

public interface RetranslationDraftProvider {

    RevisionDraft generate(PostDraftReviewSession session, PostDraftChunkRecord chunk);
}
