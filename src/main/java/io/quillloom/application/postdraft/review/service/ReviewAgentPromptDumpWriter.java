package io.quillloom.application.postdraft.review.service;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public interface ReviewAgentPromptDumpWriter {

    void dump(PromptDumpRecord record);

    static ReviewAgentPromptDumpWriter disabled() {
        return record -> {
        };
    }

    static ReviewAgentPromptDumpWriter fileBacked(Path directory) {
        return new FileBackedPromptDumpWriter(directory, Clock.systemUTC());
    }

    record PromptDumpRecord(String projectId,
                            String promptKind,
                            int attempt,
                            String anchorChunkId,
                            String workingSetChunkIds,
                            String injectedSnapshotChunkIds,
                            String injectedSnapshotSourceTypes,
                            int trimmedSnapshotCount,
                            String toolName,
                            String validationError,
                            String errorMessage,
                            String structuredOutputError,
                            String rawOutput,
                            String exceptionType,
                            String systemPrompt,
                            String userPrompt) {

        public PromptDumpRecord {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(promptKind, "promptKind");
            Objects.requireNonNull(anchorChunkId, "anchorChunkId");
            Objects.requireNonNull(workingSetChunkIds, "workingSetChunkIds");
            Objects.requireNonNull(injectedSnapshotChunkIds, "injectedSnapshotChunkIds");
            Objects.requireNonNull(injectedSnapshotSourceTypes, "injectedSnapshotSourceTypes");
            Objects.requireNonNull(exceptionType, "exceptionType");
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(userPrompt, "userPrompt");
        }
    }

    final class FileBackedPromptDumpWriter implements ReviewAgentPromptDumpWriter {
        private static final DateTimeFormatter FILE_TIMESTAMP =
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS-nnnnnnnnn").withZone(ZoneOffset.UTC);

        private final Path directory;
        private final Clock clock;

        FileBackedPromptDumpWriter(Path directory, Clock clock) {
            this.directory = Objects.requireNonNull(directory, "directory");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public void dump(PromptDumpRecord record) {
            Objects.requireNonNull(record, "record");
            try {
                Files.createDirectories(directory);
                Path output = directory.resolve(buildFileName(record));
                Files.writeString(output, render(record), StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new UncheckedIOException("Failed to write review-agent prompt dump", ex);
            }
        }

        private String buildFileName(PromptDumpRecord record) {
            String timestamp = FILE_TIMESTAMP.format(Instant.now(clock));
            return timestamp
                    + "-"
                    + sanitize(record.projectId())
                    + "-"
                    + sanitize(record.anchorChunkId())
                    + "-"
                    + sanitize(record.promptKind())
                    + "-"
                    + "attempt-" + record.attempt()
                    + "-"
                    + sanitize(record.exceptionType())
                    + ".log";
        }

        private String render(PromptDumpRecord record) {
            return """
                    projectId=%s
                    exceptionType=%s
                    promptKind=%s
                    attempt=%s
                    anchorChunkId=%s
                    workingSetChunkIds=%s
                    injectedSnapshotChunkIds=%s
                    injectedSnapshotSourceTypes=%s
                    trimmedSnapshotCount=%s
                    toolName=%s
                    validationError=%s
                    errorMessage=%s
                    structuredOutputError=%s
                    rawOutput=%s

                    [systemPrompt]
                    %s

                    [userPrompt]
                    %s
                    """.formatted(
                    record.projectId(),
                    record.exceptionType(),
                    record.promptKind(),
                    record.attempt(),
                    record.anchorChunkId(),
                    record.workingSetChunkIds(),
                    record.injectedSnapshotChunkIds(),
                    record.injectedSnapshotSourceTypes(),
                    record.trimmedSnapshotCount(),
                    nullToDash(record.toolName()),
                    nullToDash(record.validationError()),
                    nullToDash(record.errorMessage()),
                    nullToDash(record.structuredOutputError()),
                    nullToDash(record.rawOutput()),
                    record.systemPrompt(),
                    record.userPrompt()
            );
        }

        private static String sanitize(String value) {
            return value.replaceAll("[^a-zA-Z0-9._-]", "_");
        }

        private static String nullToDash(String value) {
            return value == null || value.isBlank() ? "-" : value;
        }
    }
}
