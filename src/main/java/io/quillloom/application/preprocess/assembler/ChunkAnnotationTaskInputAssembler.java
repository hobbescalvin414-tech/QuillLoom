package io.quillloom.application.preprocess.assembler;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import org.springframework.stereotype.Component;

/**
 * 将上游散落对象收敛为 Agent B 可消费的结构化任务输入。
 * 这里输出的是阶段内执行输入，不是最终 prompt 文本。
 */
@Component
public class ChunkAnnotationTaskInputAssembler {

    public ChunkAnnotationTaskInput assemble(PreprocessBookCommand command,
                                             GlobalAnalysisBundle globalAnalysis,
                                             ChunkDescriptor chunk) {
        return new ChunkAnnotationTaskInput(
                command.projectId(),
                command.title(),
                command.sourceLanguage(),
                command.targetLanguage(),
                globalAnalysis.bookAnalysis(),
                globalAnalysis.globalConstraints(),
                chunk
        );
    }
}