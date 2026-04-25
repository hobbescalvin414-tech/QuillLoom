package io.quillloom.application.preprocess.port.out;

import io.quillloom.application.preprocess.model.ChunkSegmentationPlanningResult;
import io.quillloom.application.preprocess.model.ChunkSegmentationTaskInput;

public interface ChunkSegmentationPlanGenerator {

    ChunkSegmentationPlanningResult generate(ChunkSegmentationTaskInput input);
}