package io.quillloom.application.preprocess.port.out;

import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import io.quillloom.application.preprocess.model.BookAnalysisTaskResult;

public interface BookAnalysisGenerator {

    BookAnalysisTaskResult generate(BookAnalysisTaskInput input);
}