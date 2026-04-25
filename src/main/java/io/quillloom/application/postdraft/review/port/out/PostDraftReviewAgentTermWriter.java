package io.quillloom.application.postdraft.review.port.out;

import java.util.Map;

public interface PostDraftReviewAgentTermWriter {

    Map<String, String> recordConfirmedTerms(String projectId, Map<String, String> entries);

    static PostDraftReviewAgentTermWriter noop() {
        return (projectId, entries) -> entries == null ? Map.of() : Map.copyOf(entries);
    }
}
