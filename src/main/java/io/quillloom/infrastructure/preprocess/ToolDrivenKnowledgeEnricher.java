package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.KnowledgeIndexDocument;
import io.quillloom.application.preprocess.port.out.KnowledgeEmbeddingService;
import io.quillloom.application.preprocess.port.out.KnowledgeEnricher;
import io.quillloom.application.preprocess.port.out.KnowledgeIndexRepository;
import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.domain.knowledge.CandidateTerm;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.KnowledgeEnrichmentBundle;
import io.quillloom.infrastructure.preprocess.intrinsic.IntrinsicEntityCardDraft;
import io.quillloom.infrastructure.preprocess.intrinsic.IntrinsicEntityCardPlanner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ToolDrivenKnowledgeEnricher implements KnowledgeEnricher {

    private final KnowledgeSearchTool knowledgeSearchTool;
    private final ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository;
    private final KnowledgeNeedPlanner knowledgeNeedPlanner;
    private final KnowledgeSearchGate knowledgeSearchGate;
    private final KnowledgeCardDraftNormalizer knowledgeCardDraftNormalizer;
    private final KnowledgeCardMergeService knowledgeCardMergeService;
    private final KnowledgeCardRetrievalTextBuilder retrievalTextBuilder;
    private final KnowledgeEmbeddingService knowledgeEmbeddingService;
    private final KnowledgeIndexRepository knowledgeIndexRepository;
    private final IntrinsicEntityCardPlanner intrinsicEntityCardPlanner;
    private final WorkflowTraceRecorder traceRecorder;

    @Autowired
    public ToolDrivenKnowledgeEnricher(KnowledgeSearchTool knowledgeSearchTool,
                                       ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository,
                                       KnowledgeNeedPlanner knowledgeNeedPlanner,
                                       KnowledgeSearchGate knowledgeSearchGate,
                                       KnowledgeCardDraftNormalizer knowledgeCardDraftNormalizer,
                                       KnowledgeCardMergeService knowledgeCardMergeService,
                                       KnowledgeCardRetrievalTextBuilder retrievalTextBuilder,
                                       KnowledgeEmbeddingService knowledgeEmbeddingService,
                                       KnowledgeIndexRepository knowledgeIndexRepository) {
        this(knowledgeSearchTool, projectKnowledgeBaseRepository, knowledgeNeedPlanner, knowledgeSearchGate,
                knowledgeCardDraftNormalizer, knowledgeCardMergeService, retrievalTextBuilder,
                knowledgeEmbeddingService, knowledgeIndexRepository, new IntrinsicEntityCardPlanner(), new WorkflowTraceRecorder());
    }

    public ToolDrivenKnowledgeEnricher(KnowledgeSearchTool knowledgeSearchTool,
                                       ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository,
                                       KnowledgeNeedPlanner knowledgeNeedPlanner,
                                       KnowledgeSearchGate knowledgeSearchGate,
                                       KnowledgeCardDraftNormalizer knowledgeCardDraftNormalizer,
                                       KnowledgeCardMergeService knowledgeCardMergeService,
                                       KnowledgeCardRetrievalTextBuilder retrievalTextBuilder,
                                       KnowledgeEmbeddingService knowledgeEmbeddingService,
                                       KnowledgeIndexRepository knowledgeIndexRepository,
                                       WorkflowTraceRecorder traceRecorder) {
        this(knowledgeSearchTool, projectKnowledgeBaseRepository, knowledgeNeedPlanner, knowledgeSearchGate,
                knowledgeCardDraftNormalizer, knowledgeCardMergeService, retrievalTextBuilder,
                knowledgeEmbeddingService, knowledgeIndexRepository, new IntrinsicEntityCardPlanner(), traceRecorder);
    }

    public ToolDrivenKnowledgeEnricher(KnowledgeSearchTool knowledgeSearchTool,
                                       ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository,
                                       KnowledgeNeedPlanner knowledgeNeedPlanner,
                                       KnowledgeSearchGate knowledgeSearchGate,
                                       KnowledgeCardDraftNormalizer knowledgeCardDraftNormalizer,
                                       KnowledgeCardMergeService knowledgeCardMergeService,
                                       KnowledgeCardRetrievalTextBuilder retrievalTextBuilder,
                                       KnowledgeEmbeddingService knowledgeEmbeddingService,
                                       KnowledgeIndexRepository knowledgeIndexRepository,
                                       IntrinsicEntityCardPlanner intrinsicEntityCardPlanner,
                                       WorkflowTraceRecorder traceRecorder) {
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.projectKnowledgeBaseRepository = projectKnowledgeBaseRepository;
        this.knowledgeNeedPlanner = knowledgeNeedPlanner;
        this.knowledgeSearchGate = knowledgeSearchGate;
        this.knowledgeCardDraftNormalizer = knowledgeCardDraftNormalizer;
        this.knowledgeCardMergeService = knowledgeCardMergeService;
        this.retrievalTextBuilder = retrievalTextBuilder;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
        this.knowledgeIndexRepository = knowledgeIndexRepository;
        this.intrinsicEntityCardPlanner = intrinsicEntityCardPlanner;
        this.traceRecorder = traceRecorder;
    }

    @Override
    public KnowledgeEnrichmentBundle enrich(PreprocessBookCommand command,
                                            GlobalAnalysisBundle globalAnalysis,
                                            ChunkAnnotationBundle chunkAnnotations) {
        ProjectKnowledgeBase base = projectKnowledgeBaseRepository.load(command.projectId())
                .orElse(ProjectKnowledgeBase.empty(command.projectId()));

        Map<String, KnowledgeCard> mergedCards = new LinkedHashMap<>();
        for (KnowledgeCard card : base.cards()) {
            mergedCards.put(card.cardId(), card);
        }

        Map<String, CandidateTerm> mergedCandidateTerms = new LinkedHashMap<>();
        for (CandidateTerm term : base.candidateTerms()) {
            mergedCandidateTerms.put(term.sourceTerm(), term);
        }

        for (ChunkAnnotation chunk : chunkAnnotations.chunks()) {
            List<KnowledgeNeed> plannedNeeds = knowledgeNeedPlanner.plan(chunk, command.targetLanguage());
            List<KnowledgeNeed> eligibleNeeds = knowledgeSearchGate.filterNeeds(
                    chunk,
                    new ProjectKnowledgeBase(command.projectId(), List.copyOf(mergedCards.values()), List.copyOf(mergedCandidateTerms.values())),
                    plannedNeeds
            );
            traceRecorder.record(WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_gate_evaluated", WorkflowEventStatus.SUCCEEDED,
                    chunk.chunk().coarseBlockId(), chunk.chunk().chunkId(), Map.of(
                            "plannedNeedCount", plannedNeeds.size(),
                            "eligibleNeedCount", eligibleNeeds.size(),
                            "plannedQueryCount", plannedNeeds.size(),
                            "eligibleQueryCount", eligibleNeeds.size()
                    ));
            traceRecorder.record(WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_queries_planned", WorkflowEventStatus.SUCCEEDED,
                    chunk.chunk().coarseBlockId(), chunk.chunk().chunkId(), Map.of(
                            "queries", eligibleNeeds.stream().map(this::toNeedPayload).toList()
                    ));
            if (!eligibleNeeds.isEmpty()) {
                for (KnowledgeSearchOutcome outcome : knowledgeSearchTool.search(chunk, eligibleNeeds)) {
                    if (!outcome.accepted()) {
                        traceRecorder.record(WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_card_rejected", WorkflowEventStatus.SUCCEEDED,
                                chunk.chunk().coarseBlockId(), chunk.chunk().chunkId(), Map.of(
                                        "searchOutcome", toRejectedOutcomePayload(outcome)
                                ));
                        continue;
                    }
                    OrganizedKnowledgeEvidence evidence = outcome.organizedEvidenceOptional().orElseThrow();
                    traceRecorder.record(WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_search_hit_collected", WorkflowEventStatus.SUCCEEDED,
                            chunk.chunk().coarseBlockId(), chunk.chunk().chunkId(), Map.of(
                                    "searchResult", toAcceptedOutcomePayload(outcome)
                            ));
                    KnowledgeCardDraft draft = knowledgeCardDraftNormalizer.normalize(chunk.chunk().chunkId(), safeList(chunk.entities()), evidence);
                    KnowledgeCard card = toKnowledgeCard(chunk, draft);
                    traceRecorder.record(WorkflowStage.KNOWLEDGE_ENRICHMENT, "knowledge_card_created", WorkflowEventStatus.SUCCEEDED,
                            chunk.chunk().coarseBlockId(), chunk.chunk().chunkId(), Map.of(
                                    "knowledgeCard", toKnowledgeCardPayload(card)
                            ));
                    mergeKnowledgeCard(mergedCards, card);
                }
            }
            for (CandidateTerm term : toCandidateTerms(chunk)) {
                traceRecorder.record(WorkflowStage.KNOWLEDGE_ENRICHMENT, "candidate_term_created", WorkflowEventStatus.SUCCEEDED,
                        chunk.chunk().coarseBlockId(), chunk.chunk().chunkId(), Map.of(
                                "candidateTerm", Map.of(
                                        "sourceTerm", term.sourceTerm(),
                                        "category", term.category(),
                                        "rationale", term.rationale(),
                                        "candidateTranslations", term.candidateTranslations()
                                )
                        ));
                mergedCandidateTerms.putIfAbsent(term.sourceTerm(), term);
            }
        }

        for (KnowledgeCard intrinsicCard : intrinsicEntityCardPlanner.plan(chunkAnnotations.chunks()).stream()
                .map(this::toIntrinsicKnowledgeCard)
                .toList()) {
            mergeKnowledgeCard(mergedCards, intrinsicCard);
        }

        ProjectKnowledgeBase knowledgeBase = new ProjectKnowledgeBase(command.projectId(), List.copyOf(mergedCards.values()), List.copyOf(mergedCandidateTerms.values()));
        projectKnowledgeBaseRepository.save(knowledgeBase);
        knowledgeIndexRepository.replaceProjectIndex(command.projectId(), buildIndexDocuments(knowledgeBase));
        return new KnowledgeEnrichmentBundle(knowledgeBase);
    }

    private List<KnowledgeIndexDocument> buildIndexDocuments(ProjectKnowledgeBase knowledgeBase) {
        List<KnowledgeIndexDocument> documents = new ArrayList<>();
        for (KnowledgeCard card : knowledgeBase.cards()) {
            String retrievalText = retrievalTextBuilder.build(card);
            documents.add(new KnowledgeIndexDocument(knowledgeBase.projectId(), card.cardId(), retrievalText, knowledgeEmbeddingService.embed(retrievalText)));
        }
        return List.copyOf(documents);
    }

    private void mergeKnowledgeCard(Map<String, KnowledgeCard> mergedCards, KnowledgeCard incomingCard) {
        KnowledgeCard mergeTarget = knowledgeCardMergeService.findMergeTarget(List.copyOf(mergedCards.values()), incomingCard);
        if (mergeTarget == null) {
            mergedCards.putIfAbsent(incomingCard.cardId(), incomingCard);
            return;
        }
        KnowledgeCard mergedCard = knowledgeCardMergeService.mergeInto(List.of(mergeTarget), incomingCard);
        mergedCards.put(mergeTarget.cardId(), mergedCard);
    }

    private KnowledgeCard toKnowledgeCard(ChunkAnnotation chunk, KnowledgeCardDraft draft) {
        Set<String> keywords = new LinkedHashSet<>();
        safeList(chunk.entities()).forEach(keywords::add);
        safeList(chunk.keyExpressions()).forEach(keywords::add);

        Set<String> anchorNames = new LinkedHashSet<>(safeList(draft.anchorNames()));
        safeList(chunk.entities()).forEach(anchorNames::add);

        return new KnowledgeCard(
                buildCardId(chunk, draft),
                draft.cardType(),
                defaultText(draft.title(), "Untitled knowledge card"),
                defaultText(draft.content(), ""),
                List.copyOf(keywords),
                List.copyOf(anchorNames),
                List.copyOf(safeList(draft.sourceRefs())),
                "PROJECT",
                List.copyOf(draft.applicableChunkIds())
        );
    }

    private KnowledgeCard toIntrinsicKnowledgeCard(IntrinsicEntityCardDraft draft) {
        Set<String> keywords = new LinkedHashSet<>();
        keywords.add(draft.canonicalName());
        keywords.addAll(draft.aliasSet());

        Set<String> anchorNames = new LinkedHashSet<>();
        anchorNames.add(draft.canonicalName());
        anchorNames.addAll(draft.aliasSet());

        return new KnowledgeCard(
                buildIntrinsicCardId(draft),
                KnowledgeCardType.CHARACTER_PROFILE,
                draft.canonicalName() + " 人物卡",
                defaultText(draft.roleSummary(), draft.canonicalName() + " 是书内出现的重要人物。"),
                List.copyOf(keywords),
                List.copyOf(anchorNames),
                List.of(),
                "PROJECT",
                List.copyOf(draft.evidenceChunks()),
                Map.of(
                        "intrinsic", true,
                        "canonicalName", draft.canonicalName(),
                        "aliasState", draft.aliasState().name(),
                        "confidence", draft.confidence(),
                        "firstSeenChunkId", defaultText(draft.firstSeenChunkId(), ""),
                        "aliases", List.copyOf(draft.aliasSet()),
                        "surfaceForms", List.copyOf(draft.surfaceForms())
                )
        );
    }

    private String buildIntrinsicCardId(IntrinsicEntityCardDraft draft) {
        String normalized = defaultText(draft.canonicalName(), "intrinsic-character")
                .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
        return "kc-intrinsic-" + normalized;
    }

    private List<CandidateTerm> toCandidateTerms(ChunkAnnotation chunk) {
        List<CandidateTerm> results = new ArrayList<>();
        for (String entity : safeList(chunk.entities())) {
            if (entity == null || entity.isBlank()) {
                continue;
            }
            results.add(new CandidateTerm(entity, List.of(), "entity", "Derived from chunk annotation entity for later knowledge and translation refinement."));
        }
        return List.copyOf(results);
    }

    private String buildCardId(ChunkAnnotation chunk, KnowledgeCardDraft draft) {
        String normalized = defaultText(draft.title(), "card")
                .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
        return "kc-" + chunk.chunk().chunkId() + "-" + normalized;
    }

    private Map<String, Object> toNeedPayload(KnowledgeNeed need) {
        return Map.of(
                "cardType", need.cardType().name(),
                "queryText", need.queryText(),
                "keywords", need.keywords(),
                "anchorNames", need.anchorNames(),
                "originRefs", need.originRefs(),
                "reason", need.reason(),
                "priority", need.priority()
        );
    }

    private Map<String, Object> toEvidencePayload(OrganizedKnowledgeEvidence result) {
        return Map.of(
                "cardType", result.cardType().name(),
                "title", defaultText(result.title(), ""),
                "content", defaultText(result.content(), ""),
                "anchorNames", safeList(result.anchorNames()),
                "evidenceUrls", safeList(result.evidenceUrls()),
                "originRefs", safeList(result.originRefs()),
                "searchProvider", defaultText(result.searchProvider(), ""),
                "confidence", defaultText(result.confidence(), "")
        );
    }

    private Map<String, Object> toAcceptedOutcomePayload(KnowledgeSearchOutcome outcome) {
        OrganizedKnowledgeEvidence result = outcome.organizedEvidenceOptional().orElseThrow();
        Map<String, Object> payload = new LinkedHashMap<>(toEvidencePayload(result));
        payload.put("queryText", outcome.need().queryText());
        payload.put("rawHitCount", outcome.rawHitCount());
        payload.put("filteredHitCount", outcome.filteredHitCount());
        return Map.copyOf(payload);
    }

    private Map<String, Object> toRejectedOutcomePayload(KnowledgeSearchOutcome outcome) {
        return Map.of(
                "queryText", outcome.need().queryText(),
                "cardType", outcome.need().cardType().name(),
                "rawHitCount", outcome.rawHitCount(),
                "filteredHitCount", outcome.filteredHitCount(),
                "rejectionKind", defaultText(outcome.rejectionKind(), ""),
                "rejectionReason", defaultText(outcome.rejectionReason(), "")
        );
    }

    private Map<String, Object> toKnowledgeCardPayload(KnowledgeCard card) {
        return Map.of(
                "cardId", card.cardId(),
                "cardType", card.cardType().name(),
                "title", card.title(),
                "content", card.content(),
                "keywords", card.keywords(),
                "anchorNames", card.anchorNames(),
                "sourceRefs", card.sourceRefs(),
                "scope", card.scope(),
                "applicableChunkIds", card.applicableChunkIds()
        );
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
