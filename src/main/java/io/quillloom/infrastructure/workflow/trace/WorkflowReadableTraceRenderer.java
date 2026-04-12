package io.quillloom.infrastructure.workflow.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.workflow.trace.WorkflowTraceSession;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.application.workflow.trace.model.WorkflowStageEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class WorkflowReadableTraceRenderer {

    private final ObjectMapper objectMapper;

    WorkflowReadableTraceRenderer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    String renderOverview(WorkflowTraceSession session) {
        List<WorkflowStageEvent> events = session.events();
        long chunkAnnotations = countEvent(events, WorkflowStage.CHUNK_ANNOTATION, "chunk_annotation_completed");
        long needsPlanned = sumPayloadInt(events, WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_gate_evaluated", "plannedNeedCount");
        long needsEligible = sumPayloadInt(events, WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_gate_evaluated", "eligibleNeedCount");
        long cardsCreated = countEvent(events, WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_card_created");
        long cardsRejected = countEvent(events, WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_card_rejected");
        long selectedCards = sumSelectedCards(events);
        long draftCompleted = countEvent(events, WorkflowStage.CHUNK_TRANSLATION, "chunk_translation_completed");
        return """
                runId: %s
                workflowName: %s
                projectId: %s
                eventCount: %d
                chunkAnnotations: %d
                plannedNeeds: %d
                eligibleNeeds: %d
                knowledgeCardsCreated: %d
                knowledgeCardsRejected: %d
                selectedCards: %d
                completedDrafts: %d
                """.formatted(
                session.toManifest().runId(),
                session.toManifest().workflowName(),
                session.toManifest().projectId(),
                session.toManifest().eventCount(),
                chunkAnnotations,
                needsPlanned,
                needsEligible,
                cardsCreated,
                cardsRejected,
                selectedCards,
                draftCompleted
        );
    }

    String renderPreprocessReadable(List<WorkflowStageEvent> events) {
        if (events.isEmpty()) {
            return "[no preprocess events]\n";
        }
        StringBuilder builder = new StringBuilder();
        for (WorkflowStageEvent event : events) {
            if (!event.eventType().equals("book_analysis_constraints_filtered")) {
                continue;
            }
            builder.append("eventType: ").append(event.eventType()).append("\n");
            builder.append("acceptedGlobalConstraints:\n");
            appendJson(builder, event.payload().getOrDefault("acceptedGlobalConstraints", List.of()));
            builder.append("rejectedGlobalConstraints:\n");
            appendJson(builder, event.payload().getOrDefault("rejectedGlobalConstraints", List.of()));
            builder.append("\n");
        }
        return builder.toString();
    }

    String renderChunkAnnotationsReadable(List<WorkflowStageEvent> events) {
        return renderChunkCentric(events, "compiledResult", List.of("summary", "entities", "backgroundQuestions", "translationRisks", "keyExpressions"));
    }

    String renderKnowledgeReadable(List<WorkflowStageEvent> events) {
        if (events.isEmpty()) {
            return "[no knowledge events]\n";
        }
        StringBuilder builder = new StringBuilder();
        groupedByChunk(events).forEach((chunkId, chunkEvents) -> {
            builder.append("## Chunk ").append(chunkId).append("\n");

            List<Object> plannedQueries = collectPayloadValues(chunkEvents, "queries", "knowledge_queries_planned");
            builder.append("plannedQueries:\n");
            appendJson(builder, plannedQueries.isEmpty() ? List.of() : plannedQueries.get(0));

            List<Object> acceptedCards = collectPayloadValues(chunkEvents, "knowledgeCard", "knowledge_card_created");
            builder.append("acceptedCards:\n");
            appendJson(builder, acceptedCards);

            List<Object> rejectedSearches = collectPayloadValues(chunkEvents, "searchOutcome", "knowledge_card_rejected");
            builder.append("rejectedSearches:\n");
            appendJson(builder, rejectedSearches);

            List<Object> candidateTerms = collectPayloadValues(chunkEvents, "candidateTerm", "candidate_term_created");
            builder.append("candidateTerms:\n");
            appendJson(builder, candidateTerms);
            builder.append("\n");
        });
        return builder.toString();
    }

    String renderTranslationInputReadable(List<WorkflowStageEvent> events) {
        if (events.isEmpty()) {
            return "[no translation input events]\n";
        }
        StringBuilder builder = new StringBuilder();
        groupedByChunk(events).forEach((chunkId, chunkEvents) -> {
            builder.append("## Chunk ").append(chunkId).append("\n");
            Map<String, Object> payload = firstPayloadMap(chunkEvents, "compiledResult", "translation_input_assembled");
            builder.append("confirmedTerms:\n");
            appendJson(builder, payload.getOrDefault("confirmedTerms", Map.of()));
            builder.append("selectedCards:\n");
            appendJson(builder, payload.getOrDefault("selectedCards", List.of()));
            builder.append("continuityNotes:\n");
            appendJson(builder, payload.getOrDefault("continuityNotes", List.of()));
            builder.append("localContext:\n");
            appendJson(builder, payload.getOrDefault("localContext", Map.of()));
            builder.append("coarseBlockContext:\n");
            appendJson(builder, payload.getOrDefault("coarseBlockContext", Map.of()));
            builder.append("\n");
        });
        return builder.toString();
    }

    String renderDraftReadable(List<WorkflowStageEvent> events) {
        if (events.isEmpty()) {
            return "[no draft events]\n";
        }
        StringBuilder builder = new StringBuilder();
        groupedByChunk(events).forEach((chunkId, chunkEvents) -> {
            builder.append("## Chunk ").append(chunkId).append("\n");
            Map<String, Object> payload = firstPayloadMap(chunkEvents, "compiledResult", "chunk_translation_completed");
            builder.append("translatedText:\n");
            builder.append(payload.getOrDefault("translatedText", "")).append("\n");
            builder.append("translatorCommentary:\n");
            builder.append(payload.getOrDefault("translatorCommentary", "")).append("\n");
            builder.append("decisionNotes:\n");
            appendJson(builder, payload.getOrDefault("decisionNotes", List.of()));
            builder.append("confirmedTermUpdates:\n");
            appendJson(builder, payload.getOrDefault("confirmedTermUpdates", Map.of()));
            builder.append("\n");
        });
        return builder.toString();
    }

    private String renderChunkCentric(List<WorkflowStageEvent> events,
                                      String payloadKey,
                                      List<String> fieldOrder) {
        if (events.isEmpty()) {
            return "[no events]\n";
        }
        StringBuilder builder = new StringBuilder();
        groupedByChunk(events).forEach((chunkId, chunkEvents) -> {
            builder.append("## Chunk ").append(chunkId).append("\n");
            Map<String, Object> payload = firstPayloadMap(chunkEvents, payloadKey, null);
            for (String field : fieldOrder) {
                builder.append(field).append(":\n");
                appendJson(builder, payload.getOrDefault(field, List.of()));
            }
            builder.append("\n");
        });
        return builder.toString();
    }

    private Map<String, List<WorkflowStageEvent>> groupedByChunk(List<WorkflowStageEvent> events) {
        return events.stream()
                .filter(event -> event.chunkId() != null && !event.chunkId().isBlank())
                .collect(Collectors.groupingBy(WorkflowStageEvent::chunkId, LinkedHashMap::new, Collectors.toList()));
    }

    private long countEvent(List<WorkflowStageEvent> events, WorkflowStage stage, String eventType) {
        return events.stream()
                .filter(event -> event.stage() == stage && event.eventType().equals(eventType))
                .count();
    }

    private long sumPayloadInt(List<WorkflowStageEvent> events, WorkflowStage stage, String eventType, String key) {
        return events.stream()
                .filter(event -> event.stage() == stage && event.eventType().equals(eventType))
                .mapToLong(event -> asInt(event.payload().get(key)))
                .sum();
    }

    private long sumSelectedCards(List<WorkflowStageEvent> events) {
        return events.stream()
                .filter(event -> event.stage() == WorkflowStage.TRANSLATION_INPUT && event.eventType().equals("knowledge_cards_selected"))
                .map(event -> event.payload().get("selectedCards"))
                .mapToLong(value -> value instanceof List<?> list ? list.size() : 0)
                .sum();
    }

    private List<Object> collectPayloadValues(List<WorkflowStageEvent> events, String key, String eventType) {
        List<Object> values = new ArrayList<>();
        for (WorkflowStageEvent event : events) {
            if (!event.eventType().equals(eventType)) {
                continue;
            }
            Object value = event.payload().get(key);
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstPayloadMap(List<WorkflowStageEvent> events, String key, String eventType) {
        for (WorkflowStageEvent event : events) {
            if (eventType != null && !event.eventType().equals(eventType)) {
                continue;
            }
            Object value = event.payload().get(key);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        }
        return Map.of();
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private void appendJson(StringBuilder builder, Object value) {
        try {
            builder.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)).append("\n");
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to render readable trace.", exception);
        }
    }
}
