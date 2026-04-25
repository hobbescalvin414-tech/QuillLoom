package io.quillloom.support;

import io.quillloom.application.preprocess.assembler.ChunkAnnotationTaskInputAssembler;
import io.quillloom.application.preprocess.assembler.ChunkSegmentationTaskInputAssembler;
import io.quillloom.application.preprocess.model.ChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.ChunkSegmentationPlanningResult;
import io.quillloom.application.preprocess.port.out.KnowledgeEnricher;
import io.quillloom.infrastructure.preprocess.ChunkAnnotationOrchestrator;
import io.quillloom.infrastructure.preprocess.InMemoryProjectKnowledgeBaseRepository;
import io.quillloom.infrastructure.preprocess.KnowledgeCardIdentityResolver;
import io.quillloom.infrastructure.preprocess.KnowledgeCardDraftNormalizer;
import io.quillloom.infrastructure.preprocess.KnowledgeCardMergeService;
import io.quillloom.infrastructure.preprocess.KnowledgeNeed;
import io.quillloom.infrastructure.preprocess.KnowledgeCardRetrievalTextBuilder;
import io.quillloom.infrastructure.preprocess.KnowledgeSearchOutcome;
import io.quillloom.infrastructure.preprocess.KnowledgeSearchGate;
import io.quillloom.infrastructure.preprocess.KnowledgeSearchGateProperties;
import io.quillloom.infrastructure.preprocess.KnowledgeSearchTool;
import io.quillloom.infrastructure.preprocess.NoOpKnowledgeEmbeddingService;
import io.quillloom.infrastructure.preprocess.NoOpKnowledgeIndexRepository;
import io.quillloom.infrastructure.preprocess.OrganizedKnowledgeEvidence;
import io.quillloom.infrastructure.preprocess.ParagraphView;
import io.quillloom.infrastructure.preprocess.ToolDrivenKnowledgeEnricher;
import io.quillloom.infrastructure.preprocess.chunkannotation.ChunkAnnotationLlmResult;
import io.quillloom.infrastructure.preprocess.chunkannotation.ChunkAnnotationLlmResultNormalizer;
import io.quillloom.infrastructure.preprocess.chunkannotation.ChunkAnnotationLlmResultParser;
import io.quillloom.infrastructure.preprocess.chunkannotation.ChunkAnnotationPromptRenderer;
import io.quillloom.infrastructure.preprocess.chunkannotation.LlmChunkAnnotationGenerator;
import io.quillloom.infrastructure.preprocess.chunksegmentation.ChunkDescriptorCompiler;

import java.util.ArrayList;
import java.util.List;

public final class PreprocessTestSupport {

    private static final int TARGET_CHUNK_SIZE = 800;
    private static final int MIN_PARAGRAPH_BREAK_OFFSET = 200;

    private PreprocessTestSupport() {
    }

    public static ChunkAnnotationOrchestrator createChunkAnnotator() {
        return new ChunkAnnotationOrchestrator(
                new ChunkSegmentationTaskInputAssembler(),
                input -> new ChunkSegmentationPlanningResult(buildWindowedBoundaries(input.coarseChunkBlock().sourceText())),
                new ChunkDescriptorCompiler(),
                new ChunkAnnotationTaskInputAssembler(),
                new LlmChunkAnnotationGenerator(
                        new ChunkAnnotationPromptRenderer(),
                        prompt -> new ChunkAnnotationLlmResult(
                                "占位摘要",
                                List.of("Alice"),
                                List.of("背景问题"),
                                List.of("翻译风险：全书风格画像"),
                                List.of("关键表达：约束关注")
                        ),
                        new ChunkAnnotationLlmResultNormalizer(),
                        new ChunkAnnotationLlmResultParser()
                )
        );
    }

    public static KnowledgeEnricher createKnowledgeEnricher() {
        KnowledgeSearchTool searchTool = (chunk, needs) -> List.of();
        return new ToolDrivenKnowledgeEnricher(
                searchTool,
                new InMemoryProjectKnowledgeBaseRepository(),
                (chunk, targetLanguage) -> List.of(
                        new KnowledgeNeed(
                                io.quillloom.domain.knowledge.KnowledgeCardType.CULTURAL_BACKGROUND,
                                "test knowledge need",
                                List.of("Alice"),
                                List.of("test"),
                                List.of("chunk:test#planner"),
                                "测试知识需求",
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
    }

    private static List<ChunkBoundaryPlan> buildWindowedBoundaries(String sourceText) {
        ParagraphView paragraphView = ParagraphView.from(sourceText);
        if (paragraphView.isEmpty()) {
            return List.of();
        }

        List<ChunkBoundaryPlan> boundaries = new ArrayList<>();
        int startParagraphIndex = 1;
        while (startParagraphIndex <= paragraphView.paragraphs().size()) {
            int endParagraphIndex = startParagraphIndex;
            int accumulatedLength = 0;

            while (endParagraphIndex <= paragraphView.paragraphs().size()) {
                int paragraphLength = paragraphView.paragraphAt(endParagraphIndex).rawText().length();
                int projectedLength = accumulatedLength == 0 ? paragraphLength : accumulatedLength + 2 + paragraphLength;
                if (projectedLength > TARGET_CHUNK_SIZE && accumulatedLength >= MIN_PARAGRAPH_BREAK_OFFSET) {
                    break;
                }
                accumulatedLength = projectedLength;
                endParagraphIndex++;
            }

            int finalParagraphIndex = Math.max(startParagraphIndex, endParagraphIndex - 1);
            boundaries.add(new ChunkBoundaryPlan(finalParagraphIndex, "测试用窗口切分边界"));
            startParagraphIndex = finalParagraphIndex + 1;
        }
        return List.copyOf(boundaries);
    }
}
