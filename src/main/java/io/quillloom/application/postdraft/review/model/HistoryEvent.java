package io.quillloom.application.postdraft.review.model;

public record HistoryEvent(
        String title,
        String detail
) {

    public HistoryEvent {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        title = title.trim();
        detail = detail == null ? "" : detail.trim();
    }
}
