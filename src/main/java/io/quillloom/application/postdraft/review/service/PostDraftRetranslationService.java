package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.Objects;

public class PostDraftRetranslationService {

    private final PostDraftRevisionService revisionService;

    public PostDraftRetranslationService() {
        this(new PromptBackedRetranslationDraftProvider());
    }

    public PostDraftRetranslationService(RetranslationDraftProvider draftProvider) {
        Objects.requireNonNull(draftProvider, "draftProvider");
        this.revisionService = new PostDraftRevisionService(
                (session, chunk, strategy) -> {
                    if (strategy != ReviewStrategy.RETRANSLATE) {
                        throw new IllegalArgumentException("Retranslation service only supports RETRANSLATE");
                    }
                    return draftProvider.generate(session, chunk);
                },
                (session, chunk, strategy, draft) -> {
                    throw new UnsupportedOperationException("Retranslation service does not run self-checks directly");
                }
        );
    }

    public PostDraftRetranslationService(PostDraftRevisionService revisionService) {
        this.revisionService = Objects.requireNonNull(revisionService, "revisionService");
    }

    public RevisionDraft retranslate(PostDraftReviewSession session,
                                     PostDraftChunkRecord chunk) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(chunk, "chunk");

        String sourceText = normalize(chunk.sourceText());
        if (sourceText.isBlank()) {
            throw new IllegalStateException("Retranslation requires non-blank sourceText for chunk=" + chunk.chunkId());
        }
        RevisionDraft draft = revisionService.generate(session, chunk, ReviewStrategy.RETRANSLATE);
        if (draft.revisionMode() != RevisionMode.RETRANSLATE) {
            throw new IllegalStateException("Retranslation draft must use RETRANSLATE mode");
        }
        return draft;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }
}
