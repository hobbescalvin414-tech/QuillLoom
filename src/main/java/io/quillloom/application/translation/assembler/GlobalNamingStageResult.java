package io.quillloom.application.translation.assembler;

import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;

public record GlobalNamingStageResult(
        DraftStageGlobalGlossary globalGlossary,
        GlobalAliasConsistencyTable aliasConsistencyTable
) {
}
