package io.quillloom.application.preprocess.model;

public record BookAnalysisTaskInput(
        String projectId,
        String title,
        String sourceText,
        String sourceLanguage,
        String targetLanguage
) {
}