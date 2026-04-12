package io.quillloom.infrastructure.workflow.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.workflow.trace.WorkflowTraceSession;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowTraceArtifactWriterTest {

    @Test
    void shouldWriteManifestEventsAndStageSnapshots() throws Exception {
        Path tempDir = Files.createTempDirectory("workflow-trace-writer-test");
        WorkflowTraceSession session = new WorkflowTraceSession("run-1", "draft-workflow", "project-1");
        session.append(WorkflowStage.COARSE_PLANNING, "coarse_planning_started", WorkflowEventStatus.SUCCEEDED, "block-1", null, Map.of("input", Map.of("title", "book")));
        session.append(WorkflowStage.PREPROCESS, "book_analysis_constraints_filtered", WorkflowEventStatus.SUCCEEDED, null, null, Map.of(
                "acceptedGlobalConstraints", java.util.List.of(Map.of("type", "consistency", "description", "全书命名应保持一致")),
                "rejectedGlobalConstraints", java.util.List.of(Map.of("type", "consistency", "description", "所有专有名词保留原文不译", "reasonCode", "entity-level-do-not-translate"))
        ));
        session.append(WorkflowStage.CHUNK_ANNOTATION, "chunk_annotation_completed", WorkflowEventStatus.SUCCEEDED, "block-1", "chunk-1", Map.of("compiledResult", Map.of("summary", "x")));
        session.append(WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_card_created", WorkflowEventStatus.SUCCEEDED, "block-1", "chunk-1", Map.of("knowledgeCard", Map.of("cardId", "kc-1")));
        session.append(WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_card_rejected", WorkflowEventStatus.SUCCEEDED, "block-1", "chunk-1", Map.of("searchOutcome", Map.of("queryText", "old church symbolism", "rejectionKind", "ORGANIZER_REJECTED")));
        session.append(WorkflowStage.TRANSLATION_INPUT, "translation_input_assembled", WorkflowEventStatus.SUCCEEDED, "block-1", "chunk-1", Map.of("compiledResult", Map.of("confirmedTerms", Map.of())));
        session.append(WorkflowStage.CHUNK_TRANSLATION, "chunk_translation_completed", WorkflowEventStatus.SUCCEEDED, "block-1", "chunk-1", Map.of("compiledResult", Map.of("translatedText", "draft")));

        WorkflowTraceArtifactWriter writer = new WorkflowTraceArtifactWriter(new ObjectMapper(), tempDir);
        Path runDir = writer.write(session);

        assertTrue(Files.exists(runDir.resolve("00-manifest.json")));
        assertTrue(Files.exists(runDir.resolve("00-run-overview.txt")));
        assertTrue(Files.exists(runDir.resolve("01-events.ndjson")));
        assertTrue(Files.exists(runDir.resolve("05-preprocess-readable.txt")));
        assertTrue(Files.exists(runDir.resolve("10-coarse-blocks.json")));
        assertTrue(Files.exists(runDir.resolve("10-coarse-blocks.txt")));
        assertTrue(Files.exists(runDir.resolve("30-chunk-annotations.json")));
        assertTrue(Files.exists(runDir.resolve("30-chunk-annotations.txt")));
        assertTrue(Files.exists(runDir.resolve("40-c0-knowledge.json")));
        assertTrue(Files.exists(runDir.resolve("40-c0-readable.txt")));
        assertTrue(Files.exists(runDir.resolve("50-translation-inputs.json")));
        assertTrue(Files.exists(runDir.resolve("50-translation-input-readable.txt")));
        assertTrue(Files.exists(runDir.resolve("60-chunk-drafts.txt")));
        assertTrue(Files.exists(runDir.resolve("60-draft-readable.txt")));

        String overviewText = Files.readString(runDir.resolve("00-run-overview.txt"));
        assertTrue(overviewText.contains("projectId: project-1"));
        assertTrue(overviewText.contains("knowledgeCardsCreated: 1"));
        assertTrue(overviewText.contains("knowledgeCardsRejected: 1"));

        String c0Readable = Files.readString(runDir.resolve("40-c0-readable.txt"));
        assertTrue(c0Readable.contains("## Chunk chunk-1"));
        assertTrue(c0Readable.contains("acceptedCards"));
        assertTrue(c0Readable.contains("rejectedSearches"));

        String preprocessReadable = Files.readString(runDir.resolve("05-preprocess-readable.txt"));
        assertTrue(preprocessReadable.contains("rejectedGlobalConstraints"));
        assertTrue(preprocessReadable.contains("entity-level-do-not-translate"));

        String translationReadable = Files.readString(runDir.resolve("50-translation-input-readable.txt"));
        assertTrue(translationReadable.contains("## Chunk chunk-1"));
        assertTrue(translationReadable.contains("confirmedTerms"));

        deleteRecursively(tempDir);
    }

    private void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
