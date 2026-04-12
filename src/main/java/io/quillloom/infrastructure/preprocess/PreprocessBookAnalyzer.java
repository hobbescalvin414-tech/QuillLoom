package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.assembler.BookAnalysisTaskInputAssembler;
import io.quillloom.application.preprocess.assembler.CoarseChunkPlanningTaskInputAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.port.out.BookAnalysisGenerator;
import io.quillloom.application.preprocess.port.out.BookAnalyzer;
import io.quillloom.application.preprocess.port.out.CoarseChunkPlanGenerator;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.infrastructure.preprocess.coarsechunkplanning.CoarseChunkPlanCompiler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PreprocessBookAnalyzer implements BookAnalyzer {

    private final BookAnalysisTaskInputAssembler bookAnalysisTaskInputAssembler;
    private final BookAnalysisGenerator bookAnalysisGenerator;
    private final CoarseChunkPlanningTaskInputAssembler coarseChunkPlanningTaskInputAssembler;
    private final CoarseChunkPlanGenerator coarseChunkPlanGenerator;
    private final CoarseChunkPlanCompiler coarseChunkPlanCompiler;
    private final WorkflowTraceRecorder traceRecorder;

    @Autowired
    public PreprocessBookAnalyzer(BookAnalysisTaskInputAssembler bookAnalysisTaskInputAssembler,
                                  BookAnalysisGenerator bookAnalysisGenerator,
                                  CoarseChunkPlanningTaskInputAssembler coarseChunkPlanningTaskInputAssembler,
                                  CoarseChunkPlanGenerator coarseChunkPlanGenerator,
                                  CoarseChunkPlanCompiler coarseChunkPlanCompiler) {
        this(bookAnalysisTaskInputAssembler, bookAnalysisGenerator, coarseChunkPlanningTaskInputAssembler, coarseChunkPlanGenerator, coarseChunkPlanCompiler, new WorkflowTraceRecorder());
    }
    public PreprocessBookAnalyzer(BookAnalysisTaskInputAssembler bookAnalysisTaskInputAssembler,
                                  BookAnalysisGenerator bookAnalysisGenerator,
                                  CoarseChunkPlanningTaskInputAssembler coarseChunkPlanningTaskInputAssembler,
                                  CoarseChunkPlanGenerator coarseChunkPlanGenerator,
                                  CoarseChunkPlanCompiler coarseChunkPlanCompiler,
                                  WorkflowTraceRecorder traceRecorder) {
        this.bookAnalysisTaskInputAssembler = bookAnalysisTaskInputAssembler;
        this.bookAnalysisGenerator = bookAnalysisGenerator;
        this.coarseChunkPlanningTaskInputAssembler = coarseChunkPlanningTaskInputAssembler;
        this.coarseChunkPlanGenerator = coarseChunkPlanGenerator;
        this.coarseChunkPlanCompiler = coarseChunkPlanCompiler;
        this.traceRecorder = traceRecorder;
    }

    @Override
    public GlobalAnalysisBundle analyze(PreprocessBookCommand command) {
        var bookAnalysisTaskInput = bookAnalysisTaskInputAssembler.assemble(command);
        var analysisResult = bookAnalysisGenerator.generate(bookAnalysisTaskInput);
        if (!analysisResult.tracePayload().isEmpty()) {
            traceRecorder.record(
                    WorkflowStage.PREPROCESS,
                    "book_analysis_constraints_filtered",
                    WorkflowEventStatus.SUCCEEDED,
                    null,
                    null,
                    analysisResult.tracePayload()
            );
        }
        var coarseChunkPlanningTaskInput = coarseChunkPlanningTaskInputAssembler.assemble(command);
        var coarseChunkPlanningResult = coarseChunkPlanGenerator.generate(coarseChunkPlanningTaskInput);
        var coarseChunkPlan = coarseChunkPlanCompiler.compile(command.sourceText(), coarseChunkPlanningResult);
        for (CoarseChunkBlock block : coarseChunkPlan.blocks()) {
            traceRecorder.record(
                    WorkflowStage.COARSE_PLANNING,
                    "coarse_block_emitted",
                    WorkflowEventStatus.SUCCEEDED,
                    block.blockId(),
                    null,
                    Map.of(
                            "compiledResult", Map.of(
                                    "blockId", block.blockId(),
                                    "sequence", block.sequence(),
                                    "startOffset", block.startOffset(),
                                    "endOffset", block.endOffset(),
                                    "summary", block.summary(),
                                    "boundaryHint", block.boundaryHint(),
                                    "sourceText", block.sourceText()
                            )
                    )
            );
        }
        return new GlobalAnalysisBundle(
                analysisResult.bookAnalysis(),
                analysisResult.globalConstraints(),
                coarseChunkPlan
        );
    }
}
