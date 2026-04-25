package io.quillloom.application.preprocess.port.out;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;

public interface BookAnalyzer {

    GlobalAnalysisBundle analyze(PreprocessBookCommand command);
}
