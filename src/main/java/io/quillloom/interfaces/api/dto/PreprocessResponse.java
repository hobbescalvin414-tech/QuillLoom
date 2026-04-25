package io.quillloom.interfaces.api.dto;

import io.quillloom.domain.preprocess.PreprocessDossier;

import java.util.List;

public record PreprocessResponse(
        String projectId,
        String title,
        String synopsis,
        int chunkCount,
        List<ChunkItem> chunks
) {

    public static PreprocessResponse from(PreprocessDossier dossier) {
        return new PreprocessResponse(
                dossier.project().projectId(),
                dossier.project().title(),
                dossier.globalAnalysis().bookAnalysis().synopsis(),
                dossier.chunkAnnotations().chunks().size(),
                dossier.chunkAnnotations().chunks().stream()
                        .map(chunk -> new ChunkItem(
                                chunk.chunk().chunkId(),
                                chunk.chunk().sequence(),
                                chunk.summary(),
                                chunk.entities()
                        ))
                        .toList()
        );
    }

    public record ChunkItem(
            String chunkId,
            int sequence,
            String summary,
            List<String> entities
    ) {
    }
}
