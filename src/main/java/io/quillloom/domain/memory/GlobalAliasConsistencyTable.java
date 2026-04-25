package io.quillloom.domain.memory;

import java.util.List;
import java.util.Map;

public record GlobalAliasConsistencyTable(
        List<AliasCluster> clusters,
        List<AliasCluster> unresolvedClusters,
        Map<String, Object> coverageSummary
) {

    public GlobalAliasConsistencyTable {
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
        unresolvedClusters = unresolvedClusters == null ? List.of() : List.copyOf(unresolvedClusters);
        coverageSummary = coverageSummary == null ? Map.of() : Map.copyOf(coverageSummary);
    }

    public static GlobalAliasConsistencyTable empty() {
        return new GlobalAliasConsistencyTable(List.of(), List.of(), Map.of());
    }
}
