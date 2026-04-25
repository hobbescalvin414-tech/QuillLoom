package io.quillloom.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class PostDraftReviewSmokeSupport {

    private static final DateTimeFormatter DIR_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;

    public PostDraftReviewSmokeSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path prepareOutputDir(String projectId, String chunkId) throws IOException {
        Path root = Path.of("run-output", "postdraft-review-smoke");
        Files.createDirectories(root);
        String dirName = DIR_TIME_FORMATTER.format(Instant.now())
                + "-"
                + sanitizeFileName(projectId)
                + "-"
                + sanitizeFileName(chunkId);
        return Files.createDirectories(root.resolve(dirName));
    }

    public Path prepareProjectOutputDir(String projectId) throws IOException {
        return prepareOutputDir(projectId, "project");
    }

    public void writeReport(Path outputDir,
                            String projectId,
                            String chunkId,
                            String operatorNote,
                            PostDraftChunkRecord chunk,
                            PostDraftReviewAgentResult result,
                            boolean retranslationBackendConfigured) throws IOException {
        Files.writeString(
                outputDir.resolve("result-summary.txt"),
                renderSummary(projectId, chunkId, operatorNote, chunk, result, retranslationBackendConfigured),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                outputDir.resolve("result-debug.txt"),
                renderDebug(projectId, chunkId, operatorNote, chunk, result, retranslationBackendConfigured),
                StandardCharsets.UTF_8
        );
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("result.json").toFile(), result);
    }

    public void writeProjectReport(Path outputDir,
                                   String projectId,
                                   String operatorNote,
                                   PostDraftReviewAgentResult result) throws IOException {
        Files.writeString(
                outputDir.resolve("result-summary.txt"),
                renderProjectSummary(projectId, operatorNote, result),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                outputDir.resolve("result-debug.txt"),
                renderProjectDebug(projectId, operatorNote, result),
                StandardCharsets.UTF_8
        );
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("result.json").toFile(), result);
    }

    public String renderSummary(String projectId,
                                String chunkId,
                                String operatorNote,
                                PostDraftChunkRecord chunk,
                                PostDraftReviewAgentResult result,
                                boolean retranslationBackendConfigured) {
        StringBuilder builder = new StringBuilder();
        builder.append("projectId=").append(projectId).append('\n');
        builder.append("chunkId=").append(chunkId).append('\n');
        builder.append("operatorNote=").append(blankToDash(operatorNote)).append('\n');
        builder.append("strategy=").append(result.processSummary().strategy()).append('\n');
        builder.append("problemTypes=").append(result.processSummary().problemTypes()).append('\n');
        builder.append("retranslationBackendConfigured=").append(retranslationBackendConfigured).append('\n');
        builder.append("sourcePreview=").append(preview(chunk.sourceText())).append('\n');
        builder.append("draftPreview=").append(preview(chunk.translatedText())).append('\n');
        builder.append("finalTranslatedTextPreview=").append(preview(result.finalTranslatedText())).append('\n');
        builder.append("completedChunkCount=").append(result.completedChunkResults().size()).append('\n');
        builder.append("processNote=").append(blankToDash(result.processSummary().processNote())).append('\n');
        builder.append('\n');
        builder.append("[Evidence]").append('\n');
        appendLines(builder, result.processSummary().evidenceSummaries());
        builder.append('\n');
        builder.append("[HumanReview]").append('\n');
        if (result.humanReviewRequest().isPresent()) {
            HumanReviewRequest request = result.humanReviewRequest().orElseThrow();
            builder.append("requestReason=").append(blankToDash(request.requestReason())).append('\n');
            builder.append("waitingState=").append(request.waitingState()).append('\n');
            builder.append("resumeHint=").append(blankToDash(request.resumeHint())).append('\n');
            builder.append("requestNote=").append(blankToDash(request.requestNote())).append('\n');
        } else {
            builder.append("- none").append('\n');
        }
        return builder.toString();
    }

    public String renderDebug(String projectId,
                              String chunkId,
                              String operatorNote,
                              PostDraftChunkRecord chunk,
                              PostDraftReviewAgentResult result,
                              boolean retranslationBackendConfigured) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("projectId=").append(projectId).append('\n');
        builder.append("chunkId=").append(chunkId).append('\n');
        builder.append("operatorNote=").append(blankToDash(operatorNote)).append('\n');
        builder.append("retranslationBackendConfigured=").append(retranslationBackendConfigured).append('\n');
        builder.append('\n');
        builder.append("[Chunk]").append('\n');
        builder.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(chunk)).append('\n');
        builder.append('\n');
        builder.append("[Result]").append('\n');
        builder.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)).append('\n');
        return builder.toString();
    }

    public String renderProjectSummary(String projectId,
                                       String operatorNote,
                                       PostDraftReviewAgentResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("projectId=").append(projectId).append('\n');
        builder.append("mode=project").append('\n');
        builder.append("operatorNote=").append(blankToDash(operatorNote)).append('\n');
        builder.append("strategy=").append(result.processSummary().strategy()).append('\n');
        builder.append("completedChunkCount=").append(result.completedChunkResults().size()).append('\n');
        builder.append("finalMergedTranslatedTextPreview=").append(preview(result.finalMergedTranslatedText())).append('\n');
        builder.append("processNote=").append(blankToDash(result.processSummary().processNote())).append('\n');
        builder.append('\n');
        builder.append("[CompletedChunks]").append('\n');
        if (result.completedChunkResults().isEmpty()) {
            builder.append("- none").append('\n');
        } else {
            result.completedChunkResults().forEach(outcome ->
                    builder.append(outcome.chunkId())
                            .append(" => ")
                            .append(outcome.strategy())
                            .append('\n')
            );
        }
        builder.append('\n');
        builder.append("[HumanReview]").append('\n');
        if (result.humanReviewRequest().isPresent()) {
            HumanReviewRequest request = result.humanReviewRequest().orElseThrow();
            builder.append("requestReason=").append(blankToDash(request.requestReason())).append('\n');
            builder.append("completedChunkCount=").append(request.completedChunkCount()).append('\n');
            builder.append("pendingChunkCount=").append(request.pendingChunkCount()).append('\n');
            builder.append("resumeHint=").append(blankToDash(request.resumeHint())).append('\n');
        } else {
            builder.append("- none").append('\n');
        }
        return builder.toString();
    }

    public String renderProjectDebug(String projectId,
                                     String operatorNote,
                                     PostDraftReviewAgentResult result) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("projectId=").append(projectId).append('\n');
        builder.append("mode=project").append('\n');
        builder.append("operatorNote=").append(blankToDash(operatorNote)).append('\n');
        builder.append('\n');
        builder.append("[Result]").append('\n');
        builder.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)).append('\n');
        return builder.toString();
    }

    public String sanitizeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "blank";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    public String preview(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        String normalized = text.replace("\r", "").replace("\n", "\\n").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }

    private void appendLines(StringBuilder builder, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            builder.append("- none").append('\n');
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            builder.append(i + 1).append(". ").append(lines.get(i)).append('\n');
        }
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
