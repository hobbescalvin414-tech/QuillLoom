package io.quillloom.application.postdraft.review.model;

import java.util.List;
import java.util.Objects;

public record RecordConfirmedTermsProposal(
        Action action,
        String reason,
        List<RecordConfirmedTermEntry> entries
) {

    public enum Action {
        RECORD_CONFIRMED_TERMS,
        NOT_APPLICABLE
    }

    public RecordConfirmedTermsProposal {
        action = Objects.requireNonNull(action, "action");
        reason = reason == null ? "" : reason.trim();
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (action == Action.RECORD_CONFIRMED_TERMS && entries.isEmpty()) {
            throw new IllegalArgumentException("RECORD_CONFIRMED_TERMS requires non-empty entries");
        }
        if (action == Action.NOT_APPLICABLE && !entries.isEmpty()) {
            throw new IllegalArgumentException("NOT_APPLICABLE requires empty entries");
        }
    }
}
