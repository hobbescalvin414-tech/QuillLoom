package io.quillloom.domain.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterMemorySnapshotTest {

    @Test
    void shouldDefensivelyCopyNullableCollections() {
        Map<String, String> confirmedTerms = new LinkedHashMap<>();
        confirmedTerms.put("A", "甲");
        List<String> unresolvedIssues = new ArrayList<>(List.of("issue"));
        List<String> continuityNotes = new ArrayList<>(List.of("note"));

        ChapterMemorySnapshot snapshot = new ChapterMemorySnapshot(
                "chapter-1",
                confirmedTerms,
                unresolvedIssues,
                continuityNotes
        );
        confirmedTerms.put("B", "乙");
        unresolvedIssues.add("late issue");
        continuityNotes.add("late note");

        assertEquals(Map.of("A", "甲"), snapshot.confirmedTerms());
        assertEquals(List.of("issue"), snapshot.unresolvedIssues());
        assertEquals(List.of("note"), snapshot.continuityNotes());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.continuityNotes().add("mutate"));

        ChapterMemorySnapshot empty = new ChapterMemorySnapshot("chapter-2", null, null, null);
        assertTrue(empty.confirmedTerms().isEmpty());
        assertTrue(empty.unresolvedIssues().isEmpty());
        assertTrue(empty.continuityNotes().isEmpty());
    }
}
