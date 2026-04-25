package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.HistoryEvent;
import io.quillloom.application.postdraft.review.model.HistoryLog;
import io.quillloom.application.postdraft.review.model.TranscriptStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranscriptStoreModelTest {

    @Test
    void shouldKeepRecentTranscriptEntriesWhenCompacted() {
        TranscriptStore store = new TranscriptStore(new ArrayList<>(), false)
                .append("turn-1")
                .append("turn-2")
                .append("turn-3");

        TranscriptStore compacted = store.compact(2);

        assertEquals(List.of("turn-1", "turn-2", "turn-3"), store.replay());
        assertEquals(List.of("turn-2", "turn-3"), compacted.replay());
    }

    @Test
    void shouldDefensivelyCopyTranscriptEntries() {
        ArrayList<String> entries = new ArrayList<>(List.of("turn-1"));

        TranscriptStore store = new TranscriptStore(entries, false);
        entries.add("turn-2");

        assertEquals(List.of("turn-1"), store.replay());
        assertThrows(UnsupportedOperationException.class, () -> store.entries().add("turn-3"));
    }

    @Test
    void shouldDefensivelyCopyHistoryLogEntries() {
        ArrayList<HistoryEvent> events = new ArrayList<>(List.of(
                new HistoryEvent("focus", "chunk-1")
        ));

        HistoryLog log = new HistoryLog(events);
        events.add(new HistoryEvent("tool", "read_previous_chunks"));

        assertEquals(1, log.replay().size());
        assertThrows(UnsupportedOperationException.class,
                () -> log.events().add(new HistoryEvent("x", "y")));
    }
}
