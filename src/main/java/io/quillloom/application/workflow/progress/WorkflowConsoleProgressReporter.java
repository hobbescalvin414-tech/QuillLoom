package io.quillloom.application.workflow.progress;

import io.quillloom.application.workflow.trace.model.WorkflowRunManifest;
import io.quillloom.application.workflow.trace.model.WorkflowStageEvent;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class WorkflowConsoleProgressReporter implements WorkflowProgressListener, AutoCloseable {

    private static final Duration DEFAULT_STALL_THRESHOLD = Duration.ofSeconds(20);
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    private final Consumer<String> lineSink;
    private final Clock clock;
    private final Duration stallThreshold;
    private final boolean schedulerEnabled;
    private final Duration heartbeatInterval;
    private ScheduledExecutorService executor;
    private WorkflowRunManifest currentRun;
    private WorkflowStageEvent lastEvent;
    private Instant lastProgressAt;
    private Instant lastHeartbeatAt;
    private boolean runActive;

    public WorkflowConsoleProgressReporter() {
        this(System.out, Clock.systemDefaultZone(), DEFAULT_STALL_THRESHOLD, true);
    }

    public WorkflowConsoleProgressReporter(PrintStream printStream,
                                           Clock clock,
                                           Duration stallThreshold,
                                           boolean schedulerEnabled) {
        this(printStream::println, clock, stallThreshold, schedulerEnabled);
    }

    public WorkflowConsoleProgressReporter(Consumer<String> lineSink,
                                           Clock clock,
                                           Duration stallThreshold,
                                           boolean schedulerEnabled) {
        this(lineSink, clock, stallThreshold, schedulerEnabled, DEFAULT_HEARTBEAT_INTERVAL);
    }

    public WorkflowConsoleProgressReporter(Consumer<String> lineSink,
                                           Clock clock,
                                           Duration stallThreshold,
                                           boolean schedulerEnabled,
                                           Duration heartbeatInterval) {
        this.lineSink = Objects.requireNonNull(lineSink, "lineSink must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stallThreshold = Objects.requireNonNull(stallThreshold, "stallThreshold must not be null");
        this.schedulerEnabled = schedulerEnabled;
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval must not be null");
    }

    @Override
    public synchronized void onRunStarted(WorkflowRunManifest manifest) {
        currentRun = manifest;
        runActive = true;
        lastEvent = null;
        lastProgressAt = clock.instant();
        lastHeartbeatAt = null;
        lineSink.accept("[workflow] run=" + manifest.runId() + " project=" + manifest.projectId() + " started");
        if (schedulerEnabled) {
            startScheduler();
        }
    }

    @Override
    public synchronized void onEvent(WorkflowStageEvent event) {
        lastEvent = event;
        lastProgressAt = clock.instant();
        String line = formatEvent(event);
        if (line != null && !line.isBlank()) {
            lineSink.accept(line);
        }
    }

    @Override
    public synchronized void onRunCompleted(WorkflowRunManifest manifest) {
        lineSink.accept("[workflow] run=" + manifest.runId() + " completed events=" + manifest.eventCount());
        stopRun();
    }

    @Override
    public synchronized void onRunFailed(WorkflowRunManifest manifest, Throwable error) {
        String reason = error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? error == null ? "unknown" : error.getClass().getSimpleName()
                : error.getMessage();
        lineSink.accept("[workflow] run=" + manifest.runId() + " failed reason=" + reason);
        if (error != null) {
            lineSink.accept(renderStackTrace(error));
        }
        stopRun();
    }

    public synchronized void emitHeartbeatIfStalled() {
        if (!runActive || lastEvent == null || lastProgressAt == null) {
            return;
        }
        Instant now = clock.instant();
        Duration idle = Duration.between(lastProgressAt, now);
        if (idle.compareTo(stallThreshold) < 0) {
            return;
        }
        if (lastHeartbeatAt != null && Duration.between(lastHeartbeatAt, now).compareTo(stallThreshold) < 0) {
            return;
        }
        lineSink.accept("[heartbeat] stage=" + stageLabel(lastEvent) + buildIdentity(lastEvent) + " still-running elapsed=" + idle.getSeconds() + "s");
        lastHeartbeatAt = now;
    }

    @Override
    public synchronized void close() {
        shutdownExecutor();
    }

    private void startScheduler() {
        shutdownExecutor();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "workflow-progress-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                emitHeartbeatIfStalled();
            } catch (RuntimeException ignored) {
            }
        }, heartbeatInterval.toSeconds(), heartbeatInterval.toSeconds(), TimeUnit.SECONDS);
    }

    private void stopRun() {
        runActive = false;
        lastEvent = null;
        lastProgressAt = null;
        lastHeartbeatAt = null;
        currentRun = null;
        shutdownExecutor();
    }

    private void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private String renderStackTrace(Throwable error) {
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        return buffer.toString().stripTrailing();
    }

    private String formatEvent(WorkflowStageEvent event) {
        return switch (event.eventType()) {
            case "coarse_planning_prompt_rendered" -> "[coarse] started";
            case "coarse_block_emitted" -> "[coarse] block=" + fallback(event.coarseBlockId(), "unknown") + " emitted";
            case "chunk_segmentation_prompt_rendered" -> "[segment] block=" + fallback(event.coarseBlockId(), "unknown") + " started";
            case "chunk_boundary_emitted" -> "[segment] block=" + fallback(event.coarseBlockId(), "unknown") + buildChunk(event) + " emitted";
            case "chunk_annotation_prompt_rendered" -> "[annotate]" + buildChunk(event) + " started";
            case "chunk_annotation_completed" -> "[annotate]" + buildChunk(event) + " completed";
            case "knowledge_gate_evaluated" -> "[c0]" + buildChunk(event)
                    + " gate eligibleQueries=" + payloadInt(event.payload(), "eligibleQueryCount")
                    + "/" + payloadInt(event.payload(), "plannedQueryCount");
            case "knowledge_queries_planned" -> "[c0]" + buildChunk(event)
                    + " queries=" + payloadListSize(event.payload(), "queries");
            case "knowledge_search_hit_collected" -> "[c0]" + buildChunk(event)
                    + " search-hit title=" + nestedText(event.payload(), "searchResult", "title");
            case "knowledge_card_rejected" -> "[c0]" + buildChunk(event)
                    + " card-rejected query=" + nestedText(event.payload(), "searchOutcome", "queryText")
                    + " kind=" + nestedText(event.payload(), "searchOutcome", "rejectionKind");
            case "knowledge_card_created" -> "[c0]" + buildChunk(event)
                    + " card-created title=" + nestedText(event.payload(), "knowledgeCard", "title");
            case "candidate_term_created" -> "[c0]" + buildChunk(event)
                    + " candidate-term source=" + nestedText(event.payload(), "candidateTerm", "sourceTerm");
            case "translation_input_assembly_started" -> "[assemble]" + buildChunk(event) + " started";
            case "translation_input_assembled" -> "[assemble]" + buildChunk(event)
                    + " completed cards=" + nestedListSize(event.payload(), "compiledResult", "selectedCards");
            case "chunk_translation_started" -> "[translate]" + buildChunk(event) + " started";
            case "chunk_translation_completed" -> "[translate]" + buildChunk(event) + " completed";
            default -> event.status().name().equals("FAILED") ? "[" + stageLabel(event) + "]" + buildIdentity(event) + " failed" : null;
        };
    }

    private String stageLabel(WorkflowStageEvent event) {
        return switch (event.stage()) {
            case COARSE_PLANNING -> "coarse";
            case CHUNK_SEGMENTATION -> "segment";
            case CHUNK_ANNOTATION -> "annotate";
            case KNOWLEDGE_ENRICHMENT -> "c0";
            case TRANSLATION_INPUT -> "assemble";
            case CHUNK_TRANSLATION -> "translate";
            case PREPROCESS -> "preprocess";
            case WORKFLOW -> "workflow";
        };
    }

    private String buildIdentity(WorkflowStageEvent event) {
        return buildBlock(event) + buildChunk(event);
    }

    private String buildBlock(WorkflowStageEvent event) {
        return event.coarseBlockId() == null || event.coarseBlockId().isBlank() ? "" : " block=" + event.coarseBlockId();
    }

    private String buildChunk(WorkflowStageEvent event) {
        return event.chunkId() == null || event.chunkId().isBlank() ? "" : " chunk=" + event.chunkId();
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int payloadInt(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private int payloadListSize(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value instanceof java.util.List<?> list) {
            return list.size();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int nestedListSize(Map<String, Object> payload, String parentKey, String childKey) {
        Object parent = payload == null ? null : payload.get(parentKey);
        if (parent instanceof Map<?, ?> map) {
            Object value = ((Map<String, Object>) map).get(childKey);
            if (value instanceof java.util.List<?> list) {
                return list.size();
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private String nestedText(Map<String, Object> payload, String parentKey, String childKey) {
        Object parent = payload == null ? null : payload.get(parentKey);
        if (parent instanceof Map<?, ?> map) {
            Object value = ((Map<String, Object>) map).get(childKey);
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }
}
