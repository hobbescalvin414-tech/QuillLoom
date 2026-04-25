package io.quillloom.application.preprocess.model;

public record CoarseChunkPlanningTaskInput(
        String projectId,
        String title,
        String sourceText,
        String sourceLanguage,
        String targetLanguage
) {
}