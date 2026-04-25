package io.quillloom.domain.memory;

import java.util.List;

public record AliasCluster(
        String clusterId,
        List<String> surfaceForms,
        String canonicalSourceNameOptional,
        AliasClusterState aliasState,
        String confidence,
        List<String> evidenceRefs,
        String recommendedRenderingFamily
) {

    public AliasCluster {
        surfaceForms = surfaceForms == null ? List.of() : List.copyOf(surfaceForms);
        canonicalSourceNameOptional = canonicalSourceNameOptional == null ? "" : canonicalSourceNameOptional;
        confidence = confidence == null ? "" : confidence;
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        recommendedRenderingFamily = recommendedRenderingFamily == null ? "" : recommendedRenderingFamily;
    }
}
