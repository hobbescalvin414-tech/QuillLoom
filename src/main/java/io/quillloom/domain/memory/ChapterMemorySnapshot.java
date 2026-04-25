package io.quillloom.domain.memory;

import java.util.List;
import java.util.Map;

/**
 * Chapter- or local-scope memory snapshot stored independently from runtime context windows.
 */
public record ChapterMemorySnapshot(
        String chapterId,
        Map<String, String> confirmedTerms,
        List<String> unresolvedIssues,
        List<String> continuityNotes
) {
    public ChapterMemorySnapshot {
        confirmedTerms = confirmedTerms == null ? Map.of() : Map.copyOf(confirmedTerms);
        unresolvedIssues = unresolvedIssues == null ? List.of() : List.copyOf(unresolvedIssues);
        continuityNotes = continuityNotes == null ? List.of() : List.copyOf(continuityNotes);
    }
}
