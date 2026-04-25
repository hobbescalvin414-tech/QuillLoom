package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.prompt.RevisionPromptBuilder;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.Objects;

public class PromptBackedRetranslationDraftProvider implements RetranslationDraftProvider {

    private final RevisionDraftProvider revisionDraftProvider;

    public PromptBackedRetranslationDraftProvider() {
        this(new PromptBackedRevisionDraftProvider());
    }

    public PromptBackedRetranslationDraftProvider(RevisionPromptBuilder promptBuilder) {
        this(new PromptBackedRevisionDraftProvider(promptBuilder));
    }

    public PromptBackedRetranslationDraftProvider(RevisionDraftProvider revisionDraftProvider) {
        this.revisionDraftProvider = Objects.requireNonNull(revisionDraftProvider, "revisionDraftProvider");
    }

    @Override
    public RevisionDraft generate(PostDraftReviewSession session, PostDraftChunkRecord chunk) {
        return revisionDraftProvider.generate(session, chunk, ReviewStrategy.RETRANSLATE);
    }
}
