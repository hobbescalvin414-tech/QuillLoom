package io.quillloom.infrastructure.workflow.trace;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.workflow.NovelTranslationWorkflowState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 将完整初稿和辅助文本落盘到运行目录，便于人工查看。
 */
public class WorkflowDraftArtifactWriter {

    private final Path outputRoot;

    public WorkflowDraftArtifactWriter(Path outputRoot) {
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot must not be null");
    }

    public Path write(String runId,
                      PreprocessBookCommand command,
                      NovelTranslationWorkflowState state) throws IOException {
        if (state == null || state.draftCompilation() == null) {
            throw new IllegalArgumentException("Compiled workflow state is required to write draft artifacts.");
        }
        Path runDir = outputRoot.resolve(runId);
        Files.createDirectories(runDir);

        Files.writeString(runDir.resolve("00-draft-overview.txt"), renderReadableOverview(command, state), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("draft.txt"), state.draftCompilation().mergedDraft(), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("merged-draft.txt"), state.draftCompilation().mergedDraft(), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("chunk-drafts.txt"), renderChunkDrafts(state.chunkDrafts()), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("source-sample.txt"), command.sourceText(), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("run-summary.txt"), renderSummary(command, state), StandardCharsets.UTF_8);

        return runDir;
    }

    private String renderChunkDrafts(List<ChunkTranslationDraft> chunkDrafts) {
        if (chunkDrafts == null || chunkDrafts.isEmpty()) {
            return "[no chunk drafts]\n";
        }
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (ChunkTranslationDraft draft : chunkDrafts) {
            index++;
            builder.append("## Chunk ").append(index).append("\n");
            builder.append("chunkId: ").append(draft.chunkId()).append("\n");
            builder.append("translatedText:\n").append(defaultText(draft.translatedText())).append("\n");
            if (draft.translatorCommentary() != null && !draft.translatorCommentary().isBlank()) {
                builder.append("translatorCommentary:\n").append(draft.translatorCommentary().trim()).append("\n");
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private String renderSummary(PreprocessBookCommand command,
                                 NovelTranslationWorkflowState state) {
        return """
                projectId: %s
                title: %s
                chunkCount: %d
                mergedDraftChars: %d
                """.formatted(
                command.projectId(),
                command.title(),
                state.chunkDrafts() == null ? 0 : state.chunkDrafts().size(),
                state.draftCompilation().mergedDraft() == null ? 0 : state.draftCompilation().mergedDraft().length()
        );
    }

    private String renderReadableOverview(PreprocessBookCommand command,
                                          NovelTranslationWorkflowState state) {
        return """
                projectId: %s
                title: %s
                chunkCount: %d
                mergedDraftChars: %d

                files:
                - draft.txt: 合并初稿正文
                - chunk-drafts.txt: 按 chunk 查看译文与 commentary
                - run-summary.txt: 简要运行摘要
                - source-sample.txt: 本次运行原文样本
                """.formatted(
                command.projectId(),
                command.title(),
                state.chunkDrafts() == null ? 0 : state.chunkDrafts().size(),
                state.draftCompilation().mergedDraft() == null ? 0 : state.draftCompilation().mergedDraft().length()
        );
    }

    private String defaultText(String value) {
        return value == null ? "" : value.trim();
    }
}
