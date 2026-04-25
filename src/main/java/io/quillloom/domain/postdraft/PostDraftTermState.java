package io.quillloom.domain.postdraft;

import io.quillloom.domain.translation.TranslationCandidateUpdate;

import java.util.List;
import java.util.Map;

public record PostDraftTermState(
        Map<String, String> effectiveConfirmedTerms,
        List<TranslationCandidateUpdate> effectiveCandidateTerms
) {

    public PostDraftTermState {
        effectiveConfirmedTerms = effectiveConfirmedTerms == null ? Map.of() : Map.copyOf(effectiveConfirmedTerms);
        effectiveCandidateTerms = effectiveCandidateTerms == null ? List.of() : List.copyOf(effectiveCandidateTerms);
    }
}
