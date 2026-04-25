package io.quillloom.domain.translation;

import io.quillloom.domain.memory.ExecutionContextView;

/**
 * chunk 翻译执行器的稳定输入契约。
 */
public record TranslationTaskInput(
        TranslationSourceMaterial sourceMaterial,
        ExecutionContextView executionContextView,
        TranslationRuntimeOptions runtimeOptions
) {
}