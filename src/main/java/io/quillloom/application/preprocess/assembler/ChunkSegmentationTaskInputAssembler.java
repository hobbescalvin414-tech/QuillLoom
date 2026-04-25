package io.quillloom.application.preprocess.assembler;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.ChunkSegmentationTaskInput;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import org.springframework.stereotype.Component;

@Component
public class ChunkSegmentationTaskInputAssembler {

    public ChunkSegmentationTaskInput assemble(PreprocessBookCommand command,
                                               GlobalAnalysisBundle globalAnalysis,
                                               CoarseChunkBlock coarseChunkBlock) {
        return new ChunkSegmentationTaskInput(
                command.projectId(),
                command.title(),
                command.sourceLanguage(),
                command.targetLanguage(),
                globalAnalysis.bookAnalysis(),
                globalAnalysis.globalConstraints(),
                coarseChunkBlock
        );
    }
}