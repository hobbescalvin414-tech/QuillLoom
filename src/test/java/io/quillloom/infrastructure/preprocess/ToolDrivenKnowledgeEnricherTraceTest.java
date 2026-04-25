package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.KnowledgeEmbedding;
import io.quillloom.application.preprocess.model.KnowledgeIndexDocument;
import io.quillloom.application.preprocess.port.out.KnowledgeEmbeddingService;
import io.quillloom.application.preprocess.port.out.KnowledgeIndexRepository;
import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDrivenKnowledgeEnricherTraceTest {

    @Test
    void shouldRecordGateQueriesSearchResultsCardsAndCandidateTerms() {
        WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
        traceRecorder.startRun("run-c0-trace-1", "draft-workflow", "project-c0");

        ToolDrivenKnowledgeEnricher enricher = new ToolDrivenKnowledgeEnricher(
                (chunk, needs) -> List.of(new KnowledgeSearchOutcome(
                        needs.get(0),
                        1,
                        1,
                        new OrganizedKnowledgeEvidence(
                                io.quillloom.domain.knowledge.KnowledgeCardType.CHARACTER_PROFILE,
                                "Bob Profile",
                                "Bob is a dock worker.",
                                List.of("Bob"),
                                List.of("https://example.com/bob"),
                                List.of("chunk:test#entity:1"),
                                "manual",
                                "HIGH"
                        ),
                        "",
                        ""
                )),
                new InMemoryProjectKnowledgeBaseRepository(),
                (chunk, targetLanguage) -> List.of(
                        new KnowledgeNeed(
                                io.quillloom.domain.knowledge.KnowledgeCardType.CHARACTER_PROFILE,
                                "Bob dock worker profile",
                                List.of("Bob"),
                                List.of("Bob"),
                                List.of("chunk:test#entity:1"),
                                "需要人物背景",
                                1
                        )
                ),
                new KnowledgeSearchGate(new KnowledgeSearchGateProperties()),
                new KnowledgeCardDraftNormalizer(),
                new KnowledgeCardMergeService(new KnowledgeCardIdentityResolver()),
                new KnowledgeCardRetrievalTextBuilder(),
                new KnowledgeEmbeddingService() {
                    @Override
                    public KnowledgeEmbedding embed(String text) {
                        return new KnowledgeEmbedding(List.of(), "", "");
                    }
                },
                new KnowledgeIndexRepository() {
                    @Override
                    public void replaceProjectIndex(String projectId, List<KnowledgeIndexDocument> documents) {
                    }

                    @Override
                    public List<io.quillloom.application.preprocess.model.KnowledgeIndexMatch> searchSimilar(String projectId, KnowledgeEmbedding embedding, int limit) {
                        return List.of();
                    }
                },
                traceRecorder
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-c0",
                "sample",
                "Alice met Bob in Paris. They discussed the old house and the local custom before walking to the church.",
                "en",
                "zh"
        );
        var globalAnalysis = BookAnalysisTestSupport.createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);

        enricher.enrich(command, globalAnalysis, chunkBundle);

        List<String> eventTypes = traceRecorder.snapshotEvents().stream().map(event -> event.eventType()).toList();
        assertTrue(eventTypes.contains("knowledge_gate_evaluated"));
        assertTrue(eventTypes.contains("knowledge_queries_planned"));
        assertTrue(eventTypes.contains("knowledge_search_hit_collected"));
        assertTrue(eventTypes.contains("knowledge_card_created"));
        assertTrue(eventTypes.contains("candidate_term_created"));

        traceRecorder.clear();
    }

    @Test
    void shouldRecordRejectedKnowledgeCardAndContinue() {
        WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
        traceRecorder.startRun("run-c0-trace-2", "draft-workflow", "project-c0");

        ToolDrivenKnowledgeEnricher enricher = new ToolDrivenKnowledgeEnricher(
                (chunk, needs) -> List.of(new KnowledgeSearchOutcome(
                        needs.get(0),
                        3,
                        3,
                        null,
                        "ENTITY_AMBIGUOUS",
                        "same-name venue mismatch"
                )),
                new InMemoryProjectKnowledgeBaseRepository(),
                (chunk, targetLanguage) -> List.of(
                        new KnowledgeNeed(
                                io.quillloom.domain.knowledge.KnowledgeCardType.CULTURAL_BACKGROUND,
                                "Le Conde Paris cafe history",
                                List.of("Le Conde"),
                                List.of("Le Conde", "Paris", "cafe"),
                                List.of("chunk:test#backgroundQuestion:1"),
                                "需要咖啡馆背景",
                                1
                        )
                ),
                new KnowledgeSearchGate(new KnowledgeSearchGateProperties()),
                new KnowledgeCardDraftNormalizer(),
                new KnowledgeCardMergeService(new KnowledgeCardIdentityResolver()),
                new KnowledgeCardRetrievalTextBuilder(),
                text -> new KnowledgeEmbedding(List.of(), "", ""),
                new KnowledgeIndexRepository() {
                    @Override
                    public void replaceProjectIndex(String projectId, List<KnowledgeIndexDocument> documents) {
                    }

                    @Override
                    public List<io.quillloom.application.preprocess.model.KnowledgeIndexMatch> searchSimilar(String projectId, KnowledgeEmbedding embedding, int limit) {
                        return List.of();
                    }
                },
                traceRecorder
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-c0",
                "sample",
                "Alice met Bob in Paris. They discussed the old house and the local custom before walking to the church.",
                "en",
                "zh"
        );
        var globalAnalysis = BookAnalysisTestSupport.createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);

        enricher.enrich(command, globalAnalysis, chunkBundle);

        List<String> eventTypes = traceRecorder.snapshotEvents().stream().map(event -> event.eventType()).toList();
        assertTrue(eventTypes.contains("knowledge_card_rejected"));
        assertTrue(eventTypes.contains("candidate_term_created"));

        traceRecorder.clear();
    }
}
