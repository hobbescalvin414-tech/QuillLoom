package io.quillloom.application.preprocess.assembler;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import org.springframework.stereotype.Component;

@Component
public class BookAnalysisTaskInputAssembler {

    public BookAnalysisTaskInput assemble(PreprocessBookCommand command) {
        return new BookAnalysisTaskInput(
                command.projectId(),
                command.title(),
                command.sourceText(),
                command.sourceLanguage(),
                command.targetLanguage()
        );
    }
}