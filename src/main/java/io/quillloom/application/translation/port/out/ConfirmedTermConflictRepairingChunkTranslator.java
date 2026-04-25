package io.quillloom.application.translation.port.out;

import io.quillloom.application.translation.model.ConfirmedTermConflict;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationTaskInput;

public interface ConfirmedTermConflictRepairingChunkTranslator extends ChunkTranslator {

    ChunkTranslationDraft repairConfirmedTermConflict(
            TranslationTaskInput input,
            ChunkTranslationDraft previousDraft,
            ConfirmedTermConflict conflict,
            int attempt
    );
}
