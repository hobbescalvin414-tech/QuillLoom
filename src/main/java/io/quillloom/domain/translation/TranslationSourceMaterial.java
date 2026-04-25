package io.quillloom.domain.translation;

import io.quillloom.domain.book.BookProject;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkAnnotation;

/**
 * 单个 chunk 翻译任务的稳定领域输入。
 */
public record TranslationSourceMaterial(
        BookProject project,
        BookAnalysis bookAnalysis,
        ChunkAnnotation chunk
) {
}