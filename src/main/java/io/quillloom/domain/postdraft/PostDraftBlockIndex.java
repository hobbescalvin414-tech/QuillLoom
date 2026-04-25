package io.quillloom.domain.postdraft;

import java.util.List;

public record PostDraftBlockIndex(
        String blockId,
        String summary,
        List<String> chunkIds
) {

    public PostDraftBlockIndex {
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
    }
}
