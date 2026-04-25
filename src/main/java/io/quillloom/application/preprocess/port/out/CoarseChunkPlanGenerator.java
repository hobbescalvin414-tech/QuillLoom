package io.quillloom.application.preprocess.port.out;

import io.quillloom.application.preprocess.model.CoarseChunkPlanningResult;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningTaskInput;

public interface CoarseChunkPlanGenerator {

    CoarseChunkPlanningResult generate(CoarseChunkPlanningTaskInput input);
}