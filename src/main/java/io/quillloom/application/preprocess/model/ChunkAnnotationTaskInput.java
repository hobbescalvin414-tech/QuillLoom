package io.quillloom.application.preprocess.model;

import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkDescriptor;

import java.util.List;

public record ChunkAnnotationTaskInput(
        String projectId,
        String title,
        String sourceLanguage,
        String targetLanguage,
        BookAnalysis bookAnalysis,
        List<GlobalConstraint> globalConstraints,
        ChunkDescriptor chunk
) {
}