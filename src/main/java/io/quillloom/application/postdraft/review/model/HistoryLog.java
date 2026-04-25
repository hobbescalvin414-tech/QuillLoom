package io.quillloom.application.postdraft.review.model;

import java.util.List;
import java.util.Objects;

public record HistoryLog(
        List<HistoryEvent> events
) {

    public HistoryLog {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static HistoryLog empty() {
        return new HistoryLog(List.of());
    }

    @Override
    public List<HistoryEvent> events() {
        return List.copyOf(events);
    }

    public HistoryLog add(HistoryEvent event) {
        HistoryEvent nextEvent = Objects.requireNonNull(event, "event");
        return new HistoryLog(concat(nextEvent));
    }

    public HistoryLog add(String title, String detail) {
        return add(new HistoryEvent(title, detail));
    }

    public List<HistoryEvent> replay() {
        return List.copyOf(events);
    }

    private List<HistoryEvent> concat(HistoryEvent event) {
        java.util.ArrayList<HistoryEvent> updated = new java.util.ArrayList<>(events);
        updated.add(event);
        return List.copyOf(updated);
    }
}
