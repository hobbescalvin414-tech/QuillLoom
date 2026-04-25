package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.assembler.ChunkAnnotationTaskInputAssembler;
import io.quillloom.application.preprocess.assembler.ChunkSegmentationTaskInputAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import io.quillloom.application.preprocess.model.ChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.ChunkSegmentationPlanningResult;
import io.quillloom.application.preprocess.port.out.ChunkAnnotationGenerator;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import io.quillloom.infrastructure.preprocess.chunkannotation.ChunkAnnotationLlmResult;
import io.quillloom.infrastructure.preprocess.chunkannotation.ChunkAnnotationLlmResultNormalizer;
import io.quillloom.infrastructure.preprocess.chunkannotation.ChunkAnnotationLlmResultParser;
import io.quillloom.infrastructure.preprocess.chunkannotation.ChunkAnnotationPromptRenderer;
import io.quillloom.infrastructure.preprocess.chunkannotation.LlmChunkAnnotationGenerator;
import io.quillloom.infrastructure.preprocess.chunksegmentation.ChunkDescriptorCompiler;
import io.quillloom.support.BookAnalysisTestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkAnnotationOrchestratorTest {

    @Test
    void shouldReturnEmptyBundleForBlankSourceText() {
        PreprocessBookCommand command = new PreprocessBookCommand("project-blank", "empty", "   \n\t  ", "en", "zh");
        ChunkAnnotationOrchestrator annotator = createAnnotator(input -> new ChunkSegmentationPlanningResult(List.of()));

        var bundle = annotator.annotate(command, createBookAnalyzer().analyze(command));

        assertTrue(bundle.chunks().isEmpty());
    }

    @Test
    void shouldKeepChunkDescriptorOffsetsAlignedWithSourceSlice() {
        String sourceText = "\n\n"
                + "First scene opens in Paris with Alice and Bob arguing by the river. ".repeat(8)
                + "\n\n"
                + "Second scene moves into the old house where Alice studies a faded map in silence. ".repeat(8)
                + "\n\n"
                + "Third scene follows Bob through the rain as bells echo over the bridge at dusk. ".repeat(8);
        PreprocessBookCommand command = new PreprocessBookCommand("project-1", "sample", sourceText, "en", "zh");
        ChunkAnnotationOrchestrator annotator = createAnnotator(input -> new ChunkSegmentationPlanningResult(List.of(
                new ChunkBoundaryPlan(3, "全文单 chunk")
        )));

        var bundle = annotator.annotate(command, createBookAnalyzer().analyze(command));

        assertFalse(bundle.chunks().isEmpty());

        for (int i = 0; i < bundle.chunks().size(); i++) {
            var annotation = bundle.chunks().get(i);
            var descriptor = annotation.chunk();

            assertEquals("chunk-" + (i + 1), descriptor.chunkId());
            assertEquals(i + 1, descriptor.sequence());
            assertEquals(descriptor.sourceText(), sourceText.substring(descriptor.startOffset(), descriptor.endOffset()));
            assertFalse(descriptor.sourceText().isBlank());
            assertFalse(Character.isWhitespace(descriptor.sourceText().charAt(0)));
            assertFalse(Character.isWhitespace(descriptor.sourceText().charAt(descriptor.sourceText().length() - 1)));
            assertFalse(annotation.summary().isBlank());
            assertFalse(annotation.backgroundQuestions().isEmpty());
            assertFalse(annotation.translationRisks().isEmpty());
            assertFalse(annotation.keyExpressions().isEmpty());
        }
    }

    @Test
    void shouldRespectChunkBoundariesProducedByLlmPlanning() {
        String firstChunk = "A quiet Paris evening gathers around Alice and Bob near the river while lanterns appear in the rain.";
        String secondChunk = "The old house waits beyond the bridge as their conversation turns toward family debts and missing letters.";
        String blockText = firstChunk + "\n\n" + secondChunk;
        CoarseChunkBlock block = new CoarseChunkBlock("block-1", 1, 0, blockText.length(), blockText, "粗块概括", "粗块提示");
        PreprocessBookCommand command = new PreprocessBookCommand("project-2", "sample", blockText, "en", "zh");
        var globalAnalysis = new io.quillloom.domain.preprocess.GlobalAnalysisBundle(
                createBookAnalyzer().analyze(command).bookAnalysis(),
                createBookAnalyzer().analyze(command).globalConstraints(),
                new io.quillloom.domain.preprocess.CoarseChunkPlan(List.of(block))
        );
        ChunkAnnotationOrchestrator annotator = createAnnotator(input -> new ChunkSegmentationPlanningResult(List.of(
                new ChunkBoundaryPlan(1, "第一段结束"),
                new ChunkBoundaryPlan(2, "第二段结束")
        )));

        var bundle = annotator.annotate(command, globalAnalysis);

        assertEquals(2, bundle.chunks().size());
        assertEquals(firstChunk, bundle.chunks().get(0).chunk().sourceText());
        assertEquals(secondChunk, bundle.chunks().get(1).chunk().sourceText());
    }

    @Test
    void shouldCarryBookLevelStyleAndConstraintsIntoChunkAnnotations() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-3",
                "sample",
                "Alice met Bob in Paris while the church bells echoed over the river.",
                "en",
                "zh"
        );
        ChunkAnnotationOrchestrator annotator = createAnnotator(input -> new ChunkSegmentationPlanningResult(List.of(
                new ChunkBoundaryPlan(1, "整块单 chunk")
        )));

        var bundle = annotator.annotate(command, createBookAnalyzer().analyze(command));

        assertTrue(bundle.chunks().get(0).translationRisks().stream().anyMatch(item -> item.contains("全书风格画像")));
        assertTrue(bundle.chunks().get(0).keyExpressions().stream().anyMatch(item -> item.contains("约束关注")));
    }

    @Test
    void shouldRenderChinesePromptForChunkAnnotationTask() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-4",
                "示例小说",
                "Alice met Bob in Paris.",
                "en",
                "zh"
        );
        var globalAnalysis = createBookAnalyzer().analyze(command);
        CoarseChunkBlock block = globalAnalysis.coarseChunkPlan().blocks().get(0);
        ChunkDescriptor descriptor = new ChunkDescriptorCompiler().compile(block,
                new ChunkSegmentationPlanningResult(List.of(new ChunkBoundaryPlan(1, "整块单 chunk")))).get(0);
        var taskInput = new ChunkAnnotationTaskInputAssembler().assemble(command, globalAnalysis, descriptor);
        var renderer = new ChunkAnnotationPromptRenderer();

        String prompt = renderer.render(taskInput);

        assertTrue(prompt.contains("你是小说预处理阶段的 chunk 标注助手"));
        assertTrue(prompt.contains("书名：示例小说"));
        assertTrue(prompt.contains("原文："));
        assertTrue(prompt.contains("请只输出一个 JSON 对象"));
    }

    @Test
    void shouldDelegateChunkAnnotationGenerationThroughPort() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-5",
                "sample",
                "Alice met Bob in Paris.\n\nThey walked toward the old house together.",
                "en",
                "zh"
        );
        var globalAnalysis = createBookAnalyzer().analyze(command);
        var capturedChunkIds = new ArrayList<String>();
        ChunkAnnotationGenerator generator = new ChunkAnnotationGenerator() {
            @Override
            public ChunkAnnotation generate(ChunkAnnotationTaskInput input) {
                capturedChunkIds.add(input.chunk().chunkId());
                return new ChunkAnnotation(
                        input.chunk(),
                        "摘要-" + input.chunk().chunkId(),
                        List.of(),
                        List.of("背景问题"),
                        List.of("翻译风险"),
                        List.of("关键表达")
                );
            }
        };
        ChunkAnnotationOrchestrator annotator = new ChunkAnnotationOrchestrator(
                new ChunkSegmentationTaskInputAssembler(),
                input -> new ChunkSegmentationPlanningResult(List.of(new ChunkBoundaryPlan(2, "整块单 chunk"))),
                new ChunkDescriptorCompiler(),
                new ChunkAnnotationTaskInputAssembler(),
                generator
        );

        var bundle = annotator.annotate(command, globalAnalysis);

        assertEquals(bundle.chunks().size(), capturedChunkIds.size());
        assertEquals("摘要-" + bundle.chunks().get(0).chunk().chunkId(), bundle.chunks().get(0).summary());
    }

    @Test
    void shouldRespectCoarseChunkBoundariesProducedByAgentA() {
        String firstBlock = "First block text around the river and bridge. ".repeat(20).trim();
        String secondBlock = "Second block text inside the old house and family hall. ".repeat(20).trim();
        String sourceText = firstBlock + "\n\n" + secondBlock;
        PreprocessBookCommand command = new PreprocessBookCommand("project-6", "sample", sourceText, "en", "zh");
        var baseAnalysis = createBookAnalyzer().analyze(command);
        var globalAnalysis = new io.quillloom.domain.preprocess.GlobalAnalysisBundle(
                baseAnalysis.bookAnalysis(),
                baseAnalysis.globalConstraints(),
                new io.quillloom.domain.preprocess.CoarseChunkPlan(List.of(
                        new io.quillloom.domain.preprocess.CoarseChunkBlock("block-1", 1, 0, firstBlock.length(), firstBlock, "第一粗块概概", "第一粗块"),
                        new io.quillloom.domain.preprocess.CoarseChunkBlock("block-2", 2, firstBlock.length() + 2, sourceText.length(), secondBlock, "第二粗块概概", "第二粗块")
                ))
        );

        var bundle = createAnnotator(input -> new ChunkSegmentationPlanningResult(List.of(new ChunkBoundaryPlan(1, "整块单 chunk")))).annotate(command, globalAnalysis);
        int firstBlockEnd = globalAnalysis.coarseChunkPlan().blocks().get(0).endOffset();

        assertTrue(bundle.chunks().stream().anyMatch(chunk -> chunk.chunk().endOffset() <= firstBlockEnd));
        assertTrue(bundle.chunks().stream().anyMatch(chunk -> chunk.chunk().startOffset() >= firstBlockEnd));
    }

    private PreprocessBookAnalyzer createBookAnalyzer() {
        return BookAnalysisTestSupport.createBookAnalyzer();
    }

    private ChunkAnnotationOrchestrator createAnnotator(io.quillloom.application.preprocess.port.out.ChunkSegmentationPlanGenerator segmentationPlanGenerator) {
        return new ChunkAnnotationOrchestrator(
                new ChunkSegmentationTaskInputAssembler(),
                segmentationPlanGenerator,
                new ChunkDescriptorCompiler(),
                new ChunkAnnotationTaskInputAssembler(),
                createAnnotationGenerator()
        );
    }

    private LlmChunkAnnotationGenerator createAnnotationGenerator() {
        return new LlmChunkAnnotationGenerator(
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
        );
    }
}