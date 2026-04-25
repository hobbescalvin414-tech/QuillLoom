package io.quillloom.infrastructure.translation;

import io.quillloom.application.preprocess.assembler.PreprocessDossierAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.translation.assembler.TranslationTaskInputAssembler;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.translation.TranslationTaskInput;
import io.quillloom.infrastructure.preprocess.DefaultKnowledgeRetrievalPolicyResolver;
import io.quillloom.infrastructure.preprocess.InMemoryProjectKnowledgeBaseRepository;
import io.quillloom.infrastructure.preprocess.KnowledgeCardIdentityResolver;
import io.quillloom.infrastructure.preprocess.KnowledgeCardDraftNormalizer;
import io.quillloom.infrastructure.preprocess.KnowledgeCardMergeService;
import io.quillloom.infrastructure.preprocess.KnowledgeCardRetrievalTextBuilder;
import io.quillloom.infrastructure.preprocess.KnowledgeNeed;
import io.quillloom.infrastructure.preprocess.KnowledgeSearchOutcome;
import io.quillloom.infrastructure.preprocess.KnowledgeSearchGate;
import io.quillloom.infrastructure.preprocess.KnowledgeSearchGateProperties;
import io.quillloom.infrastructure.preprocess.NoOpKnowledgeEmbeddingService;
import io.quillloom.infrastructure.preprocess.NoOpKnowledgeIndexRepository;
import io.quillloom.infrastructure.preprocess.OrganizedKnowledgeEvidence;
import io.quillloom.infrastructure.preprocess.ToolDrivenKnowledgeEnricher;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C0ToDIntegrationSmokeTest {

    @Test
    void shouldAllowDToConsumeAssemblyCardsAndEnterSupplementalLookupLoop() {
        InMemoryProjectKnowledgeBaseRepository repository = new InMemoryProjectKnowledgeBaseRepository();
        ToolDrivenKnowledgeEnricher enricher = new ToolDrivenKnowledgeEnricher(
                (chunk, needs) -> List.of(
                        new KnowledgeSearchOutcome(
                                needs.get(0),
                                1,
                                1,
                                new OrganizedKnowledgeEvidence(
                                        KnowledgeCardType.CHARACTER_PROFILE,
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
                        )
                ),
                repository,
                (chunk, targetLanguage) -> List.of(
                        new KnowledgeNeed(
                                KnowledgeCardType.CHARACTER_PROFILE,
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
                new NoOpKnowledgeEmbeddingService(),
                new NoOpKnowledgeIndexRepository()
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "integration-project",
                "sample",
                "Alice met Bob in Paris. They discussed the old house and the local custom before walking to the church.",
                "en",
                "zh"
        );
        var globalAnalysis = BookAnalysisTestSupport.createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);
        var knowledgeBundle = enricher.enrich(command, globalAnalysis, chunkBundle);
        var dossier = new PreprocessDossierAssembler().assemble(command, globalAnalysis, chunkBundle, knowledgeBundle);

        TranslationTaskInput input = new TranslationTaskInputAssembler(
                new io.quillloom.application.translation.service.RuleBasedKnowledgeCardSelector(
                        new io.quillloom.application.translation.service.RuleBasedKnowledgeRetrievalService(
                                repository,
                                new NoOpKnowledgeEmbeddingService(),
                                new NoOpKnowledgeIndexRepository(),
                                new DefaultKnowledgeRetrievalPolicyResolver()
                        )
                )
        ).assemble(
                dossier,
                dossier.chunkAnnotations().chunks().get(0),
                null,
                null
        );

        assertFalse(input.executionContextView().relatedKnowledgeCards().isEmpty());

        CapturingLookupService lookupService = new CapturingLookupService(
                new DefaultLocalKnowledgeLookupService(
                        new io.quillloom.application.translation.service.RuleBasedKnowledgeRetrievalService(
                                repository,
                                new NoOpKnowledgeEmbeddingService(),
                                new NoOpKnowledgeIndexRepository(),
                                new DefaultKnowledgeRetrievalPolicyResolver()
                        )
                )
        );
        DraftThenLookupClient client = new DraftThenLookupClient();

        LlmChunkTranslator translator = new LlmChunkTranslator(
                new TranslationPromptRenderer(),
                client,
                new ChunkTranslationLlmResultNormalizer(),
                new ChunkTranslationResultValidator(),
                new ChunkTranslationLlmResultParser(),
                lookupService,
                new RuleBasedKnowledgeLookupRequestPlanner()
        );

        var draft = translator.translate(input);

        assertEquals(2, client.capturedPrompts.size());
        assertEquals(1, lookupService.requests.size());
        assertEquals(input.sourceMaterial().chunk().chunk().chunkId(), draft.chunkId());
        assertEquals("final-with-supplement", draft.translatedText());
        assertTrue(client.capturedPrompts.get(1).contains("第 2 轮"));
    }

    private static final class DraftThenLookupClient implements LlmChunkTranslationClient {

        private final List<String> capturedPrompts = new ArrayList<>();
        private int callCount = 0;

        @Override
        public ChunkTranslationLlmResult generate(String prompt) {
            capturedPrompts.add(prompt);
            callCount++;
            if (callCount == 1) {
                return new ChunkTranslationLlmResult(
                        "draft-before-lookup",
                        "先给出初稿，再请求本地补卡。",
                        List.of(),
                        List.of(),
                        List.of(),
                        new ChunkTranslationTransitionNoteResult("", "", false),
                        new ChunkTranslationKnowledgeLookupRequestResult(
                                "MISSING_CHARACTER_CONTEXT",
                                List.of("Bob", "old house"),
                                List.of("CHARACTER_PROFILE", "SETTING_ENTRY"),
                                List.of("Bob"),
                                2
                        )
                );
            }
            return new ChunkTranslationLlmResult(
                    "final-with-supplement",
                    "已结合补卡结果修订。",
                    List.of(),
                    List.of(),
                    List.of(),
                    new ChunkTranslationTransitionNoteResult("", "", false)
            );
        }
    }

    private static final class CapturingLookupService implements io.quillloom.application.translation.port.out.LocalKnowledgeLookupService {

        private final DefaultLocalKnowledgeLookupService delegate;
        private final List<io.quillloom.application.translation.runtime.KnowledgeCardLookupRequest> requests = new ArrayList<>();

        private CapturingLookupService(DefaultLocalKnowledgeLookupService delegate) {
            this.delegate = delegate;
        }

        @Override
        public io.quillloom.application.translation.runtime.KnowledgeCardLookupResponse lookup(TranslationTaskInput input,
                                                                                               io.quillloom.application.translation.runtime.KnowledgeCardLookupRequest request) {
            requests.add(request);
            return delegate.lookup(input, request);
        }
    }
}
