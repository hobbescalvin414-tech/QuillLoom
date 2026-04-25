package io.quillloom.application.preprocess.assembler;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningTaskInput;
import org.springframework.stereotype.Component;

@Component
public class CoarseChunkPlanningTaskInputAssembler {

    public CoarseChunkPlanningTaskInput assemble(PreprocessBookCommand command) {
        return new CoarseChunkPlanningTaskInput(
                command.projectId(),
                command.title(),
                command.sourceText(),
                command.sourceLanguage(),
                command.targetLanguage()
        );
    }
}