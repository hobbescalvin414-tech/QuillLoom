package io.quillloom.domain.postdraft;

import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;

import java.util.List;

public record PostDraftContinuationContext(
        String projectId,
        List<PostDraftChunkRecord> chunks,
        List<PostDraftBlockIndex> blockIndexes,
        PostDraftTermState termState,
        DraftStageGlobalGlossary glossarySnapshot,
        GlobalAliasConsistencyTable aliasSnapshot,
        String mergedDraftText,
        ProjectKnowledgeBase knowledgeBase
) {

    public PostDraftContinuationContext {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        blockIndexes = blockIndexes == null ? List.of() : List.copyOf(blockIndexes);
        glossarySnapshot = glossarySnapshot == null ? DraftStageGlobalGlossary.empty() : glossarySnapshot;
        aliasSnapshot = aliasSnapshot == null ? GlobalAliasConsistencyTable.empty() : aliasSnapshot;
    }
}
