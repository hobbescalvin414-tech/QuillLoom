package io.quillloom.interfaces.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record DraftCompilationRequest(
        @NotBlank String projectId,
        @NotEmpty List<@Valid ChunkDraftBlock> chunkDrafts
) {

    public record ChunkDraftBlock(
            @NotBlank String chunkId,
            @NotBlank String translatedText
    ) {
    }
}
