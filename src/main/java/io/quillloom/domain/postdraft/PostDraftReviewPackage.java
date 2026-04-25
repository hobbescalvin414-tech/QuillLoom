package io.quillloom.domain.postdraft;

import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;

import java.time.Instant;
import java.util.List;

public record PostDraftReviewPackage(
        String projectId,
        String packageVersion,
        String sourceLanguage,
        String targetLanguage,
        String sourceDocumentDigest,
        Instant createdAt,
        List<PostDraftChunkRecord> chunks,
        List<PostDraftBlockIndex> blockIndexes,
        PostDraftTermState termState,
        DraftStageGlobalGlossary glossarySnapshot,
        GlobalAliasConsistencyTable aliasSnapshot,
        String mergedDraftText
) {

    public PostDraftReviewPackage {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        blockIndexes = blockIndexes == null ? List.of() : List.copyOf(blockIndexes);
        glossarySnapshot = glossarySnapshot == null ? DraftStageGlobalGlossary.empty() : glossarySnapshot;
        aliasSnapshot = aliasSnapshot == null ? GlobalAliasConsistencyTable.empty() : aliasSnapshot;
    }
}
