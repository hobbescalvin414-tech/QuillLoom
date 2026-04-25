package io.quillloom.application.preprocess.assembler;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import io.quillloom.domain.preprocess.CoarseChunkPlan;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkAnnotationTaskInputAssemblerTest {

    @Test
    void shouldAssembleStageLocalStructuredContextForChunkAnnotation() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-1",
                "示例小说",
                "Alice met Bob in Paris.",
                "en",
                "zh"
        );
        GlobalAnalysisBundle globalAnalysis = new GlobalAnalysisBundle(
                new BookAnalysis(
                        "全书概要",
                        "叙事结构",
                        "克制、冷静、带悬疑感",
                        List.of("风险一"),
                        List.of("策略一")
                ),
                List.of(new GlobalConstraint("c1", "style", "保持人名与地名译法一致")),
                new CoarseChunkPlan(List.of(
                        new CoarseChunkBlock("block-1", 1, 0, 24, "Alice met Bob in Paris.", "按自然段形成粗块")
                ))
        );
        ChunkDescriptor chunk = new ChunkDescriptor("chunk-1", 1, 0, 24, "Alice met Bob in Paris.");

        ChunkAnnotationTaskInputAssembler assembler = new ChunkAnnotationTaskInputAssembler();
        var input = assembler.assemble(command, globalAnalysis, chunk);

        assertEquals("project-1", input.projectId());
        assertEquals("示例小说", input.title());
        assertEquals("en", input.sourceLanguage());
        assertEquals("zh", input.targetLanguage());
        assertEquals("克制、冷静、带悬疑感", input.bookAnalysis().styleProfile());
        assertEquals("保持人名与地名译法一致", input.globalConstraints().get(0).description());
        assertEquals("chunk-1", input.chunk().chunkId());
    }
}