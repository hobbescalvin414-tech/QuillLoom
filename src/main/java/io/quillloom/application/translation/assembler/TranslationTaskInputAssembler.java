package io.quillloom.application.translation.assembler;

import io.quillloom.application.translation.port.out.KnowledgeCardSelector;
import io.quillloom.application.translation.service.RuleBasedKnowledgeCardSelector;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.CoarseBlockContext;
import io.quillloom.domain.memory.ExecutionContextView;
import io.quillloom.domain.memory.LocalSourceContext;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.domain.translation.TranslationSourceMaterial;
import io.quillloom.domain.translation.TranslationTaskInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TranslationTaskInputAssembler {

    private final KnowledgeCardSelector knowledgeCardSelector;
    private final WorkflowTraceRecorder traceRecorder;
    private final GlobalNamingStageAssembler globalNamingStageAssembler;

    public TranslationTaskInputAssembler() {
        this(new RuleBasedKnowledgeCardSelector(), new WorkflowTraceRecorder(), new GlobalNamingStageAssembler());
    }

    @Autowired
    public TranslationTaskInputAssembler(KnowledgeCardSelector knowledgeCardSelector) {
        this(knowledgeCardSelector, new WorkflowTraceRecorder(), new GlobalNamingStageAssembler());
    }

    public TranslationTaskInputAssembler(KnowledgeCardSelector knowledgeCardSelector,
                                         WorkflowTraceRecorder traceRecorder) {
        this(knowledgeCardSelector, traceRecorder, new GlobalNamingStageAssembler());
    }

    public TranslationTaskInputAssembler(KnowledgeCardSelector knowledgeCardSelector,
                                         WorkflowTraceRecorder traceRecorder,
                                         GlobalNamingStageAssembler globalNamingStageAssembler) {
        this.knowledgeCardSelector = knowledgeCardSelector;
        this.traceRecorder = traceRecorder;
        this.globalNamingStageAssembler = globalNamingStageAssembler;
    }

    public TranslationTaskInput assemble(PreprocessDossier dossier,
                                         ChunkAnnotation chunk,
                                         ProjectMemorySnapshot projectMemory,
                                         ChapterMemorySnapshot chapterMemory) {
        return assemble(dossier, chunk, projectMemory, chapterMemory, List.of(), TranslationRuntimeOptions.defaults());
    }

    public TranslationTaskInput assemble(PreprocessDossier dossier,
                                         ChunkAnnotation chunk,
                                         ProjectMemorySnapshot projectMemory,
                                         ChapterMemorySnapshot chapterMemory,
                                         TranslationRuntimeOptions runtimeOptions) {
        return assemble(dossier, chunk, projectMemory, chapterMemory, List.of(), runtimeOptions);
    }

    public TranslationTaskInput assemble(PreprocessDossier dossier,
                                         ChunkAnnotation chunk,
                                         ProjectMemorySnapshot projectMemory,
                                         ChapterMemorySnapshot chapterMemory,
                                         List<ChunkTranslationDraft> completedDrafts,
                                         TranslationRuntimeOptions runtimeOptions) {
        traceRecorder.record(WorkflowStage.TRANSLATION_INPUT, "translation_input_assembly_started", WorkflowEventStatus.STARTED, chunk.chunk().coarseBlockId(), chunk.chunk().chunkId(), Map.of());

        Map<String, String> confirmedTerms = projectMemory == null ? Map.of() : projectMemory.confirmedTerms();
        List<io.quillloom.domain.translation.TranslationCandidateUpdate> candidateTermUpdates = projectMemory == null ? List.of() : projectMemory.candidateTermUpdates();
        List<String> continuityNotes = chapterMemory == null ? List.of() : chapterMemory.continuityNotes();

        LocalSourceContext localSourceContext = buildLocalSourceContext(dossier, chunk, completedDrafts, runtimeOptions);
        CoarseBlockContext coarseBlockContext = buildCoarseBlockContext(dossier, chunk);
        List<KnowledgeCard> selectedCards = knowledgeCardSelector.selectForChunk(chunk, dossier.knowledgeEnrichment().projectKnowledgeBase(), runtimeOptions);
        GlobalNamingStageResult globalNamingStageResult = globalNamingStageAssembler.assemble(
                chunk,
                projectMemory,
                dossier.knowledgeEnrichment().projectKnowledgeBase(),
                selectedCards
        );

        traceRecorder.record(WorkflowStage.TRANSLATION_INPUT, "knowledge_cards_selected", WorkflowEventStatus.SUCCEEDED, chunk.chunk().coarseBlockId(), chunk.chunk().chunkId(), Map.of("selectedCards", selectedCards.stream().map(this::toKnowledgeCardPayload).toList()));
        traceRecorder.record(WorkflowStage.TRANSLATION_INPUT, "local_context_built", WorkflowEventStatus.SUCCEEDED, chunk.chunk().coarseBlockId(), chunk.chunk().chunkId(), Map.of(
                "localContext", toLocalContextPayload(localSourceContext),
                "coarseBlockContext", toCoarseBlockPayload(coarseBlockContext),
                "continuityNotes", continuityNotes
        ));

        ExecutionContextView executionContextView = new ExecutionContextView(
                confirmedTerms,
                candidateTermUpdates,
                localSourceContext,
                coarseBlockContext,
                globalNamingStageResult.globalGlossary(),
                globalNamingStageResult.aliasConsistencyTable(),
                selectedCards,
                dossier.globalAnalysis().globalConstraints(),
                continuityNotes
        );

        TranslationSourceMaterial sourceMaterial = new TranslationSourceMaterial(dossier.project(), dossier.globalAnalysis().bookAnalysis(), chunk);
        TranslationTaskInput taskInput = new TranslationTaskInput(sourceMaterial, executionContextView, runtimeOptions);
        traceRecorder.record(WorkflowStage.TRANSLATION_INPUT, "translation_input_assembled", WorkflowEventStatus.SUCCEEDED, chunk.chunk().coarseBlockId(), chunk.chunk().chunkId(), Map.of(
                "compiledResult", Map.of(
                        "confirmedTerms", confirmedTerms,
                        "candidateTermUpdates", candidateTermUpdates,
                        "draftStageGlobalGlossary", toDraftStageGlobalGlossaryPayload(globalNamingStageResult.globalGlossary()),
                        "globalAliasConsistencyTable", toGlobalAliasConsistencyTablePayload(globalNamingStageResult.aliasConsistencyTable()),
                        "localContext", toLocalContextPayload(localSourceContext),
                        "coarseBlockContext", toCoarseBlockPayload(coarseBlockContext),
                        "selectedCards", selectedCards.stream().map(this::toKnowledgeCardPayload).toList(),
                        "continuityNotes", continuityNotes
                )
        ));
        return taskInput;
    }

    private LocalSourceContext buildLocalSourceContext(PreprocessDossier dossier, ChunkAnnotation chunk, List<ChunkTranslationDraft> completedDrafts, TranslationRuntimeOptions runtimeOptions) {
        List<ChunkAnnotation> chunks = dossier.chunkAnnotations().chunks();
        int currentIndex = chunks.indexOf(chunk);
        if (currentIndex < 0) {
            return LocalSourceContext.empty();
        }

        Map<String, ChunkTranslationDraft> draftByChunkId = indexDraftsByChunkId(completedDrafts);
        return new LocalSourceContext(
                collectPreviousSourceTexts(chunks, currentIndex, runtimeOptions.sourceContextWindowSize()),
                collectPreviousTranslatedTexts(chunks, currentIndex, runtimeOptions.sourceContextWindowSize(), draftByChunkId),
                collectNextSourceTexts(chunks, currentIndex, runtimeOptions.sourceContextWindowSize()),
                collectPreviousSummaries(chunks, currentIndex, runtimeOptions.summaryContextWindowSize()),
                collectNextSummaries(chunks, currentIndex, runtimeOptions.summaryContextWindowSize())
        );
    }

    private CoarseBlockContext buildCoarseBlockContext(PreprocessDossier dossier, ChunkAnnotation chunk) {
        String coarseBlockId = chunk.chunk().coarseBlockId();
        if (coarseBlockId == null || coarseBlockId.isBlank()) {
            return CoarseBlockContext.empty();
        }
        List<CoarseChunkBlock> blocks = dossier.globalAnalysis().coarseChunkPlan().blocks();
        int currentIndex = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (coarseBlockId.equals(blocks.get(i).blockId())) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            return CoarseBlockContext.empty();
        }
        CoarseChunkBlock current = blocks.get(currentIndex);
        CoarseChunkBlock previous = currentIndex > 0 ? blocks.get(currentIndex - 1) : null;
        CoarseChunkBlock next = currentIndex + 1 < blocks.size() ? blocks.get(currentIndex + 1) : null;
        List<ChunkAnnotation> chunksInCurrentBlock = dossier.chunkAnnotations().chunks().stream().filter(item -> coarseBlockId.equals(item.chunk().coarseBlockId())).toList();
        int chunkCountInCurrentBlock = chunksInCurrentBlock.size();
        int chunkIndexInCurrentBlock = -1;
        for (int i = 0; i < chunksInCurrentBlock.size(); i++) {
            if (chunk.chunk().chunkId().equals(chunksInCurrentBlock.get(i).chunk().chunkId())) {
                chunkIndexInCurrentBlock = i + 1;
                break;
            }
        }
        if (chunkIndexInCurrentBlock < 0) {
            return CoarseBlockContext.empty();
        }
        return new CoarseBlockContext(current.blockId(), nullToEmpty(current.summary()), chunkIndexInCurrentBlock, chunkCountInCurrentBlock, chunkIndexInCurrentBlock == 1, chunkIndexInCurrentBlock == chunkCountInCurrentBlock, previous == null ? null : previous.blockId(), previous == null ? "" : nullToEmpty(previous.summary()), next == null ? null : next.blockId(), next == null ? "" : nullToEmpty(next.summary()));
    }

    private Map<String, ChunkTranslationDraft> indexDraftsByChunkId(List<ChunkTranslationDraft> completedDrafts) {
        Map<String, ChunkTranslationDraft> results = new LinkedHashMap<>();
        for (ChunkTranslationDraft draft : completedDrafts) {
            results.put(draft.chunkId(), draft);
        }
        return results;
    }

    private List<String> collectPreviousSourceTexts(List<ChunkAnnotation> chunks, int currentIndex, int windowSize) {
        List<String> results = new ArrayList<>();
        int start = Math.max(0, currentIndex - windowSize);
        for (int i = start; i < currentIndex; i++) {
            results.add(chunks.get(i).chunk().sourceText());
        }
        return List.copyOf(results);
    }

    private List<String> collectPreviousTranslatedTexts(List<ChunkAnnotation> chunks, int currentIndex, int windowSize, Map<String, ChunkTranslationDraft> draftByChunkId) {
        List<String> results = new ArrayList<>();
        int start = Math.max(0, currentIndex - windowSize);
        for (int i = start; i < currentIndex; i++) {
            ChunkAnnotation previousChunk = chunks.get(i);
            ChunkTranslationDraft draft = draftByChunkId.get(previousChunk.chunk().chunkId());
            if (draft != null) {
                results.add(draft.translatedText());
            }
        }
        return List.copyOf(results);
    }

    private List<String> collectNextSourceTexts(List<ChunkAnnotation> chunks, int currentIndex, int windowSize) {
        List<String> results = new ArrayList<>();
        int end = Math.min(chunks.size(), currentIndex + 1 + windowSize);
        for (int i = currentIndex + 1; i < end; i++) {
            results.add(chunks.get(i).chunk().sourceText());
        }
        return List.copyOf(results);
    }

    private List<String> collectPreviousSummaries(List<ChunkAnnotation> chunks, int currentIndex, int windowSize) {
        List<String> results = new ArrayList<>();
        int start = Math.max(0, currentIndex - windowSize);
        for (int i = start; i < currentIndex; i++) {
            results.add(chunks.get(i).summary());
        }
        return List.copyOf(results);
    }

    private List<String> collectNextSummaries(List<ChunkAnnotation> chunks, int currentIndex, int windowSize) {
        List<String> results = new ArrayList<>();
        int end = Math.min(chunks.size(), currentIndex + 1 + windowSize);
        for (int i = currentIndex + 1; i < end; i++) {
            results.add(chunks.get(i).summary());
        }
        return List.copyOf(results);
    }

    private Map<String, Object> toKnowledgeCardPayload(KnowledgeCard card) {
        return Map.of("cardId", card.cardId(), "cardType", card.cardType().name(), "title", card.title(), "content", card.content(), "keywords", card.keywords(), "anchorNames", card.anchorNames(), "sourceRefs", card.sourceRefs(), "scope", card.scope(), "applicableChunkIds", card.applicableChunkIds());
    }

    private Map<String, Object> toDraftStageGlobalGlossaryPayload(io.quillloom.domain.memory.DraftStageGlobalGlossary glossary) {
        return Map.of(
                "hardEntries", glossary.hardEntries().stream().map(entry -> Map.of(
                        "sourceTerm", entry.sourceTerm(),
                        "targetTerm", entry.targetTerm(),
                        "entryStrength", entry.entryStrength().name(),
                        "sourceKind", entry.sourceKind().name(),
                        "evidenceRefs", entry.evidenceRefs(),
                        "notes", entry.notes()
                )).toList(),
                "softEntries", glossary.softEntries().stream().map(entry -> Map.of(
                        "sourceTerm", entry.sourceTerm(),
                        "targetTerm", entry.targetTerm(),
                        "entryStrength", entry.entryStrength().name(),
                        "sourceKind", entry.sourceKind().name(),
                        "evidenceRefs", entry.evidenceRefs(),
                        "notes", entry.notes()
                )).toList(),
                "coverageSummary", glossary.coverageSummary()
        );
    }

    private Map<String, Object> toGlobalAliasConsistencyTablePayload(io.quillloom.domain.memory.GlobalAliasConsistencyTable table) {
        return Map.of(
                "clusters", table.clusters().stream().map(cluster -> Map.of(
                        "clusterId", cluster.clusterId(),
                        "surfaceForms", cluster.surfaceForms(),
                        "canonicalSourceNameOptional", cluster.canonicalSourceNameOptional(),
                        "aliasState", cluster.aliasState().name(),
                        "confidence", cluster.confidence(),
                        "evidenceRefs", cluster.evidenceRefs(),
                        "recommendedRenderingFamily", cluster.recommendedRenderingFamily()
                )).toList(),
                "unresolvedClusters", table.unresolvedClusters().stream().map(cluster -> Map.of(
                        "clusterId", cluster.clusterId(),
                        "surfaceForms", cluster.surfaceForms()
                )).toList(),
                "coverageSummary", table.coverageSummary()
        );
    }

    private Map<String, Object> toLocalContextPayload(LocalSourceContext localSourceContext) {
        return Map.of("previousChunkSourceTexts", localSourceContext.previousChunkSourceTexts(), "previousChunkTranslatedTexts", localSourceContext.previousChunkTranslatedTexts(), "nextChunkSourceTexts", localSourceContext.nextChunkSourceTexts(), "previousChunkSummaries", localSourceContext.previousChunkSummaries(), "nextChunkSummaries", localSourceContext.nextChunkSummaries());
    }

    private Map<String, Object> toCoarseBlockPayload(CoarseBlockContext coarseBlockContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("blockId", coarseBlockContext.currentBlockId());
        payload.put("summary", coarseBlockContext.currentBlockSummary());
        payload.put("chunkIndexInBlock", coarseBlockContext.chunkIndexInCurrentBlock());
        payload.put("chunkCountInBlock", coarseBlockContext.chunkCountInCurrentBlock());
        payload.put("firstChunkInBlock", coarseBlockContext.firstChunkInCurrentBlock());
        payload.put("lastChunkInBlock", coarseBlockContext.lastChunkInCurrentBlock());
        payload.put("previousBlockId", coarseBlockContext.previousBlockId());
        payload.put("previousBlockSummary", coarseBlockContext.previousBlockSummary());
        payload.put("nextBlockId", coarseBlockContext.nextBlockId());
        payload.put("nextBlockSummary", coarseBlockContext.nextBlockSummary());
        return payload;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
