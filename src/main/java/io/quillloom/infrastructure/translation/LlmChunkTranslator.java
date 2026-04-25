package io.quillloom.infrastructure.translation;

import io.quillloom.application.translation.model.ConfirmedTermConflict;
import io.quillloom.application.translation.port.out.ConfirmedTermConflictRepairingChunkTranslator;
import io.quillloom.application.translation.port.out.LocalKnowledgeLookupService;
import io.quillloom.application.translation.runtime.KnowledgeCardLookupRequest;
import io.quillloom.application.translation.runtime.KnowledgeCardLookupResponse;
import io.quillloom.application.translation.runtime.KnowledgeGapReason;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationTaskInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class LlmChunkTranslator implements ConfirmedTermConflictRepairingChunkTranslator {

    private static final String REVISION_ROUND_FALLBACK_TYPE = "revision-round-fallback";

    private final TranslationPromptRenderer promptRenderer;
    private final LlmChunkTranslationClient llmClient;
    private final ChunkTranslationLlmResultNormalizer resultNormalizer;
    private final ChunkTranslationResultValidator resultValidator;
    private final ChunkTranslationLlmResultParser resultParser;
    private final TranslatedTextIssueDetector translatedTextIssueDetector;
    private final RuleBasedKnowledgeLookupRequestPlanner knowledgeLookupRequestPlanner;
    private final LocalKnowledgeLookupService localKnowledgeLookupService;
    private final WorkflowTraceRecorder traceRecorder;

    public LlmChunkTranslator(TranslationPromptRenderer promptRenderer,
                              LlmChunkTranslationClient llmClient,
                              ChunkTranslationLlmResultNormalizer resultNormalizer,
                              ChunkTranslationResultValidator resultValidator,
                              ChunkTranslationLlmResultParser resultParser) {
        this(promptRenderer, llmClient, resultNormalizer, resultValidator, resultParser, new TranslatedTextIssueDetector(), new RuleBasedKnowledgeLookupRequestPlanner(), new NoOpLocalKnowledgeLookupService(), new WorkflowTraceRecorder());
    }

    public LlmChunkTranslator(TranslationPromptRenderer promptRenderer,
                              LlmChunkTranslationClient llmClient,
                              ChunkTranslationLlmResultNormalizer resultNormalizer,
                              ChunkTranslationResultValidator resultValidator,
                              ChunkTranslationLlmResultParser resultParser,
                              RuleBasedKnowledgeLookupRequestPlanner knowledgeLookupRequestPlanner,
                              LocalKnowledgeLookupService localKnowledgeLookupService) {
        this(promptRenderer, llmClient, resultNormalizer, resultValidator, resultParser, new TranslatedTextIssueDetector(), knowledgeLookupRequestPlanner, localKnowledgeLookupService, new WorkflowTraceRecorder());
    }

    public LlmChunkTranslator(TranslationPromptRenderer promptRenderer,
                              LlmChunkTranslationClient llmClient,
                              ChunkTranslationLlmResultNormalizer resultNormalizer,
                              ChunkTranslationResultValidator resultValidator,
                              ChunkTranslationLlmResultParser resultParser,
                              TranslatedTextIssueDetector translatedTextIssueDetector,
                              RuleBasedKnowledgeLookupRequestPlanner knowledgeLookupRequestPlanner,
                              LocalKnowledgeLookupService localKnowledgeLookupService,
                              WorkflowTraceRecorder traceRecorder) {
        this.promptRenderer = promptRenderer;
        this.llmClient = llmClient;
        this.resultNormalizer = resultNormalizer;
        this.resultValidator = resultValidator;
        this.resultParser = resultParser;
        this.translatedTextIssueDetector = translatedTextIssueDetector;
        this.knowledgeLookupRequestPlanner = knowledgeLookupRequestPlanner;
        this.localKnowledgeLookupService = localKnowledgeLookupService;
        this.traceRecorder = traceRecorder;
    }

    @Autowired
    public LlmChunkTranslator(TranslationPromptRenderer promptRenderer,
                              LlmChunkTranslationClient llmClient,
                              ChunkTranslationLlmResultNormalizer resultNormalizer,
                              ChunkTranslationResultValidator resultValidator,
                              ChunkTranslationLlmResultParser resultParser,
                              LocalKnowledgeLookupService localKnowledgeLookupService,
                              RuleBasedKnowledgeLookupRequestPlanner knowledgeLookupRequestPlanner) {
        this(promptRenderer, llmClient, resultNormalizer, resultValidator, resultParser, new TranslatedTextIssueDetector(), knowledgeLookupRequestPlanner, localKnowledgeLookupService, new WorkflowTraceRecorder());
    }

    @Override
    public ChunkTranslationDraft translate(TranslationTaskInput input) {
        traceRecorder.record(WorkflowStage.CHUNK_TRANSLATION, "chunk_translation_started", WorkflowEventStatus.STARTED, input.sourceMaterial().chunk().chunk().coarseBlockId(), input.sourceMaterial().chunk().chunk().chunkId(), Map.of());
        ChunkTranslationLlmResult draftRoundResult = executeRound(input, promptRenderer.renderDraftRound(input), "draft");
        if (shouldStopAfterDraftRound(input, draftRoundResult)) {
            ChunkTranslationDraft draft = resultParser.parse(input, draftRoundResult);
            recordCompleted(input, draft);
            return draft;
        }

        KnowledgeCardLookupResponse lookupResponse = lookupSupplementalKnowledge(input, draftRoundResult);
        ChunkTranslationLlmResult finalResult = executeRevisionRoundWithFallback(input, draftRoundResult, lookupResponse);
        ChunkTranslationDraft draft = resultParser.parse(input, finalResult);
        recordCompleted(input, draft);
        return draft;
    }

    @Override
    public ChunkTranslationDraft repairConfirmedTermConflict(TranslationTaskInput input,
                                                             ChunkTranslationDraft previousDraft,
                                                             ConfirmedTermConflict conflict,
                                                             int attempt) {
        String prompt = promptRenderer.renderConfirmedTermConflictRepair(input, previousDraft, conflict, attempt);
        ChunkTranslationLlmResult repairedResult = executeRound(input, prompt, "confirmed-term-conflict-repair-" + attempt);
        return resultParser.parse(input, repairedResult);
    }

    private ChunkTranslationLlmResult executeRound(TranslationTaskInput input, String prompt, String roundLabel) {
        traceRecorder.record(WorkflowStage.CHUNK_TRANSLATION, "chunk_translation_prompt_rendered", WorkflowEventStatus.SUCCEEDED, input.sourceMaterial().chunk().chunk().coarseBlockId(), input.sourceMaterial().chunk().chunk().chunkId(), Map.of("round", roundLabel, "prompt", Map.of("text", prompt)));
        LlmChunkTranslationClientResponse response = llmClient.generateDetailed(prompt);
        ChunkTranslationLlmResult rawResult = response.result();
        traceRecorder.record(WorkflowStage.CHUNK_TRANSLATION, "chunk_translation_llm_responded", WorkflowEventStatus.SUCCEEDED, input.sourceMaterial().chunk().chunk().coarseBlockId(), input.sourceMaterial().chunk().chunk().chunkId(), Map.of("round", roundLabel, "rawResponse", Map.of("text", response.rawResponse() == null ? String.valueOf(rawResult) : response.rawResponse())));
        ChunkTranslationLlmResult normalizedResult = resultNormalizer.normalize(input, rawResult);
        ChunkTranslationLlmResult validated = resultValidator.validate(input, normalizedResult);
        traceRecorder.record(WorkflowStage.CHUNK_TRANSLATION, "chunk_translation_normalized", WorkflowEventStatus.SUCCEEDED, input.sourceMaterial().chunk().chunk().coarseBlockId(), input.sourceMaterial().chunk().chunk().chunkId(), Map.of("round", roundLabel, "normalizedResult", Map.of("translatedText", validated.translatedText(), "translatorCommentary", validated.translatorCommentary())));
        return validated;
    }

    private ChunkTranslationLlmResult executeRevisionRoundWithFallback(TranslationTaskInput input, ChunkTranslationLlmResult draftRoundResult, KnowledgeCardLookupResponse lookupResponse) {
        try {
            List<TranslatedTextIssue> textIssues = translatedTextIssueDetector.detect(
                    input.sourceMaterial().project().targetLanguage(),
                    draftRoundResult.translatedText()
            );
            String prompt = promptRenderer.renderRevisionRound(input, draftRoundResult, textIssues) + renderSupplementalKnowledgePrompt(lookupResponse);
            return executeRound(input, prompt, "revision");
        } catch (ChunkTranslationStructuredOutputException exception) {
            return markRevisionRoundFallback(draftRoundResult, exception);
        }
    }

    private void recordCompleted(TranslationTaskInput input, ChunkTranslationDraft draft) {
        traceRecorder.record(WorkflowStage.CHUNK_TRANSLATION, "chunk_translation_completed", WorkflowEventStatus.SUCCEEDED, input.sourceMaterial().chunk().chunk().coarseBlockId(), input.sourceMaterial().chunk().chunk().chunkId(), Map.of("compiledResult", Map.of("chunkId", draft.chunkId(), "translatedText", draft.translatedText(), "translatorCommentary", draft.translatorCommentary(), "decisionNotes", draft.decisionNotes(), "confirmedTermUpdates", draft.confirmedTermUpdates(), "candidateUpdates", draft.candidateUpdates(), "transitionNote", draft.transitionNote())));
    }

    private boolean shouldStopAfterDraftRound(TranslationTaskInput input, ChunkTranslationLlmResult draftRoundResult) {
        if (draftRoundResult == null) {
            return false;
        }
        boolean hasTextBoundaryIssues = !translatedTextIssueDetector.detect(
                input.sourceMaterial().project().targetLanguage(),
                draftRoundResult.translatedText()
        ).isEmpty();
        return draftRoundResult.decisionNotes().isEmpty()
                && draftRoundResult.candidateUpdates().isEmpty()
                && draftRoundResult.knowledgeLookupRequest() == null
                && !draftRoundResult.transitionNote().boundaryAdjustmentSuggested()
                && !hasTextBoundaryIssues;
    }

    private KnowledgeCardLookupResponse lookupSupplementalKnowledge(TranslationTaskInput input, ChunkTranslationLlmResult draftRoundResult) {
        KnowledgeCardLookupRequest request = resolveLookupRequest(input, draftRoundResult);
        if (request == null) {
            return null;
        }
        return localKnowledgeLookupService.lookup(input, request);
    }

    private KnowledgeCardLookupRequest resolveLookupRequest(TranslationTaskInput input, ChunkTranslationLlmResult draftRoundResult) {
        KnowledgeCardLookupRequest explicitRequest = toRuntimeRequest(input, draftRoundResult.knowledgeLookupRequest());
        if (explicitRequest != null) {
            return explicitRequest;
        }
        return knowledgeLookupRequestPlanner.plan(input, draftRoundResult);
    }

    private KnowledgeCardLookupRequest toRuntimeRequest(TranslationTaskInput input, ChunkTranslationKnowledgeLookupRequestResult request) {
        if (request == null || request.queryTerms() == null || request.queryTerms().isEmpty()) {
            return null;
        }
        List<KnowledgeCardType> requestedTypes = request.requestedTypes().stream().map(this::parseCardType).filter(type -> type != null).toList();
        KnowledgeGapReason reason = parseReason(request.reason());
        int limit = request.limit() == null || request.limit() <= 0 ? 3 : Math.min(request.limit(), 3);
        return new KnowledgeCardLookupRequest(input.sourceMaterial().chunk().chunk().chunkId() + "-llm-lookup", input.sourceMaterial().chunk().chunk().chunkId(), reason, request.queryTerms(), requestedTypes, request.anchors() == null ? List.of() : request.anchors(), limit);
    }

    private KnowledgeGapReason parseReason(String value) {
        if (value == null || value.isBlank()) {
            return KnowledgeGapReason.GENERAL_BACKGROUND_GAP;
        }
        try {
            return KnowledgeGapReason.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return KnowledgeGapReason.GENERAL_BACKGROUND_GAP;
        }
    }

    private KnowledgeCardType parseCardType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return KnowledgeCardType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String renderSupplementalKnowledgePrompt(KnowledgeCardLookupResponse lookupResponse) {
        if (lookupResponse == null || lookupResponse.cards() == null || lookupResponse.cards().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\n【本地知识库补卡】\n");
        builder.append("以下知识卡来自当前项目的本地知识库，只服务本 chunk 本轮修订，请据此补足背景判断，但不要改写稳定契约边界。\n");
        lookupResponse.cards().forEach(card -> appendSupplementalCard(builder, card));
        builder.append("\n");
        return builder.toString();
    }

    private void appendSupplementalCard(StringBuilder builder, KnowledgeCard card) {
        builder.append("- 类型：").append(card.cardType()).append("\n");
        builder.append("  标题：").append(nullToEmpty(card.title())).append("\n");
        builder.append("  内容：").append(nullToEmpty(card.content())).append("\n");
    }

    private ChunkTranslationLlmResult markRevisionRoundFallback(ChunkTranslationLlmResult draftRoundResult, RuntimeException exception) {
        List<ChunkTranslationDecisionNoteResult> decisionNotes = new ArrayList<>(draftRoundResult.decisionNotes());
        decisionNotes.add(new ChunkTranslationDecisionNoteResult(REVISION_ROUND_FALLBACK_TYPE, "current-chunk", "第 2 轮修订失败，已回退到第 1 轮初稿结果。", sanitizeFallbackRecommendation(exception)));
        return new ChunkTranslationLlmResult(draftRoundResult.translatedText(), draftRoundResult.translatorCommentary(), List.copyOf(decisionNotes), draftRoundResult.confirmedTermUpdates(), draftRoundResult.candidateUpdates(), draftRoundResult.transitionNote(), null);
    }

    private String sanitizeFallbackRecommendation(RuntimeException exception) {
        String message = exception == null ? "" : exception.getMessage();
        if (message == null || message.isBlank()) {
            return "保留第 1 轮结果，并在后续流程中关注该 chunk。";
        }
        return "保留第 1 轮结果；修订失败原因：" + message.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class NoOpLocalKnowledgeLookupService implements LocalKnowledgeLookupService {
        @Override
        public KnowledgeCardLookupResponse lookup(TranslationTaskInput input, KnowledgeCardLookupRequest request) {
            if (request == null) {
                return null;
            }
            return KnowledgeCardLookupResponse.empty(request, "当前运行未启用本地知识库补卡");
        }
    }
}
