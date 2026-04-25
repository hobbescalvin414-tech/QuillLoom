package io.quillloom.domain.memory;

import io.quillloom.domain.translation.TranslationCandidateUpdate;

import java.util.List;
import java.util.Map;

/**
 * Project-scope long-lived memory. It is distinct from transient workflow state.
 */
public record ProjectMemorySnapshot(
        String projectId,
        Map<String, String> confirmedTerms,
        List<String> stylePolicies,
        List<String> canonFacts,
        List<TranslationCandidateUpdate> candidateTermUpdates
) {

    public ProjectMemorySnapshot {
        confirmedTerms = confirmedTerms == null ? Map.of() : Map.copyOf(confirmedTerms);
        stylePolicies = stylePolicies == null ? List.of() : List.copyOf(stylePolicies);
        canonFacts = canonFacts == null ? List.of() : List.copyOf(canonFacts);
        candidateTermUpdates = candidateTermUpdates == null ? List.of() : List.copyOf(candidateTermUpdates);
    }

    public ProjectMemorySnapshot(String projectId,
                                 Map<String, String> confirmedTerms,
                                 List<String> stylePolicies,
                                 List<String> canonFacts) {
        this(projectId, confirmedTerms, stylePolicies, canonFacts, List.of());
    }
}
