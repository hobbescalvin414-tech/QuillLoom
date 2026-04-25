package io.quillloom.application.preprocess.port.out;

import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import io.quillloom.domain.preprocess.ChunkAnnotation;

/**
 * Agent B 的执行能力端口。
 * 后续无论是启发式实现还是 LangChain4j/LLM 实现，都应统一收口到这里。
 */
public interface ChunkAnnotationGenerator {

    ChunkAnnotation generate(ChunkAnnotationTaskInput input);
}