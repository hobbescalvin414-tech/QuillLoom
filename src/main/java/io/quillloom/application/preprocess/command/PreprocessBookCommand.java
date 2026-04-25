package io.quillloom.application.preprocess.command;

/**
 * 用于构建预处理总产物的应用命令。
 *- 这是哪个项目
 * - 这本书或文本叫什么
 * - 原文是什么
 * - 源语言和目标语言是什么
 *
 */
public record PreprocessBookCommand(
        String projectId,
        String title,
        String sourceText,
        String sourceLanguage,
        String targetLanguage
) {
}
