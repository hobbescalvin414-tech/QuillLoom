package io.quillloom.infrastructure.preprocess.intrinsic;

import java.util.List;
import java.util.Set;

public record IntrinsicEntityCardDraft(
        String canonicalName,
        Set<String> aliasSet,
        Set<String> surfaceForms,
        List<String> evidenceChunks,
        String firstSeenChunkId,
        String roleSummary,
        IntrinsicAliasState aliasState,
        String confidence
) {
}
