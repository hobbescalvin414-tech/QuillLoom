package io.quillloom.interfaces.api.dto;

import jakarta.validation.constraints.NotBlank;

public record WorkflowDraftRunRequest(
        @NotBlank String projectId,
        @NotBlank String title,
        @NotBlank String sourceText,
        @NotBlank String sourceLanguage,
        @NotBlank String targetLanguage,
        String chapterId
) {
}
