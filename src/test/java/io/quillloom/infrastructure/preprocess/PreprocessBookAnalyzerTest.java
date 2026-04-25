package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.assembler.BookAnalysisTaskInputAssembler;
import io.quillloom.application.preprocess.assembler.CoarseChunkPlanningTaskInputAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import io.quillloom.application.preprocess.model.BookAnalysisTaskResult;
import io.quillloom.application.preprocess.model.CoarseChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningResult;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningTaskInput;
import io.quillloom.application.preprocess.port.out.BookAnalysisGenerator;
import io.quillloom.application.preprocess.port.out.CoarseChunkPlanGenerator;
import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.CoarseChunkPlan;
import io.quillloom.infrastructure.preprocess.coarsechunkplanning.CoarseChunkPlanCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PreprocessBookAnalyzerTest {

    @Test
    void shouldOrchestrateBookAnalysisAndCoarsePlanningWithoutInliningExecutionLogic() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-1",
                "示例小说",
                "Alice met Bob in Paris.\n\nThey walked toward the old house.",
                "en",
                "zh"
        );
        BookAnalysisTaskInput expectedBookInput = new BookAnalysisTaskInput(
                "project-1",
                "示例小说",
                command.sourceText(),
                "en",
                "zh"
        );
        CoarseChunkPlanningTaskInput expectedPlanningInput = new CoarseChunkPlanningTaskInput(
                "project-1",
                "示例小说",
                command.sourceText(),
                "en",
                "zh"
        );
        BookAnalysis expectedAnalysis = new BookAnalysis(
                "全书概要",
                "叙事结构",
                "克制",
                List.of("风险一"),
                List.of("策略一")
        );
        List<GlobalConstraint> expectedConstraints = List.of(
                new GlobalConstraint("c1", "style", "保持一致")
        );
        BookAnalysisTaskResult generatorResult = new BookAnalysisTaskResult(expectedAnalysis, expectedConstraints);
        CoarseChunkPlanningResult planningResult = new CoarseChunkPlanningResult(List.of(
                new CoarseChunkBoundaryPlan(2, "全文单块", "测试边界")
        ));
        RecordingBookAnalysisGenerator bookAnalysisGenerator = new RecordingBookAnalysisGenerator(generatorResult);
        RecordingCoarseChunkPlanGenerator coarseChunkPlanGenerator = new RecordingCoarseChunkPlanGenerator(planningResult);
        RecordingCoarseChunkPlanCompiler coarseChunkPlanCompiler = new RecordingCoarseChunkPlanCompiler(CoarseChunkPlan.empty());

        PreprocessBookAnalyzer analyzer = new PreprocessBookAnalyzer(
                new BookAnalysisTaskInputAssembler(),
                bookAnalysisGenerator,
                new CoarseChunkPlanningTaskInputAssembler(),
                coarseChunkPlanGenerator,
                coarseChunkPlanCompiler
        );

        var result = analyzer.analyze(command);

        assertEquals(expectedBookInput, bookAnalysisGenerator.capturedInput);
        assertEquals(expectedPlanningInput, coarseChunkPlanGenerator.capturedInput);
        assertSame(command.sourceText(), coarseChunkPlanCompiler.capturedSourceText);
        assertSame(planningResult, coarseChunkPlanCompiler.capturedPlanningResult);
        assertSame(expectedAnalysis, result.bookAnalysis());
        assertEquals(expectedConstraints, result.globalConstraints());
        assertSame(coarseChunkPlanCompiler.planToReturn, result.coarseChunkPlan());
    }

    private static final class RecordingBookAnalysisGenerator implements BookAnalysisGenerator {

        private final BookAnalysisTaskResult resultToReturn;
        private BookAnalysisTaskInput capturedInput;

        private RecordingBookAnalysisGenerator(BookAnalysisTaskResult resultToReturn) {
            this.resultToReturn = resultToReturn;
        }

        @Override
        public BookAnalysisTaskResult generate(BookAnalysisTaskInput input) {
            this.capturedInput = input;
            return resultToReturn;
        }
    }

    private static final class RecordingCoarseChunkPlanGenerator implements CoarseChunkPlanGenerator {

        private final CoarseChunkPlanningResult resultToReturn;
        private CoarseChunkPlanningTaskInput capturedInput;

        private RecordingCoarseChunkPlanGenerator(CoarseChunkPlanningResult resultToReturn) {
            this.resultToReturn = resultToReturn;
        }

        @Override
        public CoarseChunkPlanningResult generate(CoarseChunkPlanningTaskInput input) {
            this.capturedInput = input;
            return resultToReturn;
        }
    }

    private static final class RecordingCoarseChunkPlanCompiler extends CoarseChunkPlanCompiler {

        private final CoarseChunkPlan planToReturn;
        private String capturedSourceText;
        private CoarseChunkPlanningResult capturedPlanningResult;

        private RecordingCoarseChunkPlanCompiler(CoarseChunkPlan planToReturn) {
            this.planToReturn = planToReturn;
        }

        @Override
        public CoarseChunkPlan compile(String sourceText, CoarseChunkPlanningResult planningResult) {
            this.capturedSourceText = sourceText;
            this.capturedPlanningResult = planningResult;
            return planToReturn;
        }
    }
}