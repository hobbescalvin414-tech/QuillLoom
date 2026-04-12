package io.quillloom.infrastructure.workflow.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.workflow.trace.WorkflowTraceSession;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.application.workflow.trace.model.WorkflowStageEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class WorkflowTraceArtifactWriter {

    private static final Map<WorkflowStage, String> STAGE_FILE_PREFIX = Map.of(
            WorkflowStage.PREPROCESS, "05-preprocess",
            WorkflowStage.COARSE_PLANNING, "10-coarse-blocks",
            WorkflowStage.CHUNK_SEGMENTATION, "20-chunk-segmentation",
            WorkflowStage.CHUNK_ANNOTATION, "30-chunk-annotations",
            WorkflowStage.KNOWLEDGE_ENRICHMENT, "40-c0-knowledge",
            WorkflowStage.TRANSLATION_INPUT, "50-translation-inputs",
            WorkflowStage.CHUNK_TRANSLATION, "60-chunk-drafts"
    );

    private final ObjectMapper objectMapper;
    private final Path outputRoot;
    private final WorkflowReadableTraceRenderer readableRenderer;

    public WorkflowTraceArtifactWriter(ObjectMapper objectMapper, Path outputRoot) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .copy()
                .findAndRegisterModules();
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot must not be null");
        this.readableRenderer = new WorkflowReadableTraceRenderer(this.objectMapper);
    }

    public Path write(WorkflowTraceSession session) throws IOException {
        Path runDir = outputRoot.resolve(session.toManifest().runId());
        Files.createDirectories(runDir);

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(runDir.resolve("00-manifest.json").toFile(), session.toManifest());
        Files.writeString(runDir.resolve("00-run-overview.txt"), readableRenderer.renderOverview(session), StandardCharsets.UTF_8);

        try (BufferedWriter writer = Files.newBufferedWriter(runDir.resolve("01-events.ndjson"), StandardCharsets.UTF_8)) {
            for (WorkflowStageEvent event : session.events()) {
                writer.write(objectMapper.writeValueAsString(event));
                writer.newLine();
            }
        }

        Map<WorkflowStage, List<WorkflowStageEvent>> grouped = session.events().stream()
                .collect(Collectors.groupingBy(WorkflowStageEvent::stage, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<WorkflowStage, String> entry : STAGE_FILE_PREFIX.entrySet()) {
            List<WorkflowStageEvent> events = grouped.getOrDefault(entry.getKey(), List.of());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve(entry.getValue() + ".json").toFile(), events);
            Files.writeString(runDir.resolve(entry.getValue() + ".txt"), renderText(events), StandardCharsets.UTF_8);
        }
        Files.writeString(runDir.resolve("05-preprocess-readable.txt"),
                readableRenderer.renderPreprocessReadable(grouped.getOrDefault(WorkflowStage.PREPROCESS, List.of())),
                StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("30-annotations-readable.txt"),
                readableRenderer.renderChunkAnnotationsReadable(grouped.getOrDefault(WorkflowStage.CHUNK_ANNOTATION, List.of())),
                StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("40-c0-readable.txt"),
                readableRenderer.renderKnowledgeReadable(grouped.getOrDefault(WorkflowStage.KNOWLEDGE_ENRICHMENT, List.of())),
                StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("50-translation-input-readable.txt"),
                readableRenderer.renderTranslationInputReadable(grouped.getOrDefault(WorkflowStage.TRANSLATION_INPUT, List.of())),
                StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("60-draft-readable.txt"),
                readableRenderer.renderDraftReadable(grouped.getOrDefault(WorkflowStage.CHUNK_TRANSLATION, List.of())),
                StandardCharsets.UTF_8);

        return runDir;
    }

    private String renderText(List<WorkflowStageEvent> events) {
        if (events.isEmpty()) {
            return "[no events]\n";
        }
        StringBuilder builder = new StringBuilder();
        for (WorkflowStageEvent event : events) {
            builder.append("sequence: ").append(event.sequence()).append("\n");
            builder.append("stage: ").append(event.stage()).append("\n");
            builder.append("eventType: ").append(event.eventType()).append("\n");
            if (event.coarseBlockId() != null) {
                builder.append("coarseBlockId: ").append(event.coarseBlockId()).append("\n");
            }
            if (event.chunkId() != null) {
                builder.append("chunkId: ").append(event.chunkId()).append("\n");
            }
            builder.append("status: ").append(event.status()).append("\n");
            builder.append("payload:\n").append(toPrettyJson(event.payload())).append("\n\n");
        }
        return builder.toString();
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to render trace payload.", exception);
        }
    }
}
