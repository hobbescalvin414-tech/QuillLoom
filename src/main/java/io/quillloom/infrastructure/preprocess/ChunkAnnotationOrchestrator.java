package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.assembler.ChunkAnnotationTaskInputAssembler;
import io.quillloom.application.preprocess.assembler.ChunkSegmentationTaskInputAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.port.out.ChunkAnnotationGenerator;
import io.quillloom.application.preprocess.port.out.ChunkAnnotator;
import io.quillloom.application.preprocess.port.out.ChunkSegmentationPlanGenerator;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.infrastructure.preprocess.chunksegmentation.ChunkDescriptorCompiler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ChunkAnnotationOrchestrator implements ChunkAnnotator {

    private final ChunkSegmentationTaskInputAssembler chunkSegmentationTaskInputAssembler;
    private final ChunkSegmentationPlanGenerator chunkSegmentationPlanGenerator;
    private final ChunkDescriptorCompiler chunkDescriptorCompiler;
    private final ChunkAnnotationTaskInputAssembler taskInputAssembler;
    private final ChunkAnnotationGenerator annotationGenerator;
    private final WorkflowTraceRecorder traceRecorder;

    @Autowired
    public ChunkAnnotationOrchestrator(ChunkSegmentationTaskInputAssembler chunkSegmentationTaskInputAssembler,
                                       ChunkSegmentationPlanGenerator chunkSegmentationPlanGenerator,
                                       ChunkDescriptorCompiler chunkDescriptorCompiler,
                                       ChunkAnnotationTaskInputAssembler taskInputAssembler,
                                       ChunkAnnotationGenerator annotationGenerator) {
        this(chunkSegmentationTaskInputAssembler, chunkSegmentationPlanGenerator, chunkDescriptorCompiler, taskInputAssembler, annotationGenerator, new WorkflowTraceRecorder());
    }
    public ChunkAnnotationOrchestrator(ChunkSegmentationTaskInputAssembler chunkSegmentationTaskInputAssembler,
                                       ChunkSegmentationPlanGenerator chunkSegmentationPlanGenerator,
                                       ChunkDescriptorCompiler chunkDescriptorCompiler,
                                       ChunkAnnotationTaskInputAssembler taskInputAssembler,
                                       ChunkAnnotationGenerator annotationGenerator,
                                       WorkflowTraceRecorder traceRecorder) {
        this.chunkSegmentationTaskInputAssembler = chunkSegmentationTaskInputAssembler;
        this.chunkSegmentationPlanGenerator = chunkSegmentationPlanGenerator;
        this.chunkDescriptorCompiler = chunkDescriptorCompiler;
        this.taskInputAssembler = taskInputAssembler;
        this.annotationGenerator = annotationGenerator;
        this.traceRecorder = traceRecorder;
    }

    @Override
    public ChunkAnnotationBundle annotate(PreprocessBookCommand command, GlobalAnalysisBundle globalAnalysis) {
        List<ChunkDescriptor> descriptors = new ArrayList<>();
        int sequence = 1;

        for (CoarseChunkBlock block : globalAnalysis.coarseChunkPlan().blocks()) {
            var segmentationTaskInput = chunkSegmentationTaskInputAssembler.assemble(command, globalAnalysis, block);
            var segmentationPlanningResult = chunkSegmentationPlanGenerator.generate(segmentationTaskInput);
            for (ChunkDescriptor descriptor : chunkDescriptorCompiler.compile(block, segmentationPlanningResult)) {
                ChunkDescriptor renumbered = new ChunkDescriptor(
                        "chunk-" + sequence,
                        sequence,
                        descriptor.coarseBlockId(),
                        descriptor.startOffset(),
                        descriptor.endOffset(),
                        descriptor.sourceText()
                );
                descriptors.add(renumbered);
                traceRecorder.record(
                        WorkflowStage.CHUNK_SEGMENTATION,
                        "chunk_boundary_emitted",
                        WorkflowEventStatus.SUCCEEDED,
                        renumbered.coarseBlockId(),
                        renumbered.chunkId(),
                        Map.of(
                                "compiledResult", Map.of(
                                        "chunkId", renumbered.chunkId(),
                                        "sequence", renumbered.sequence(),
                                        "coarseBlockId", renumbered.coarseBlockId(),
                                        "startOffset", renumbered.startOffset(),
                                        "endOffset", renumbered.endOffset(),
                                        "sourceText", renumbered.sourceText()
                                )
                        )
                );
                sequence++;
            }
        }

        return new ChunkAnnotationBundle(
                descriptors.stream()
                        .map(chunk -> taskInputAssembler.assemble(command, globalAnalysis, chunk))
                        .map(annotationGenerator::generate)
                        .toList()
        );
    }
}