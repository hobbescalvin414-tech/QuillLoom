package io.quillloom.infrastructure.preprocess.bookanalysis;

import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import io.quillloom.application.preprocess.model.BookAnalysisTaskResult;
import io.quillloom.application.preprocess.port.out.BookAnalysisGenerator;
import org.springframework.stereotype.Component;

/**
 * Agent A 的 LLM 全书分析执行器。
 * 它复用提示词渲染、结果规范化与解析链，只替换模型调用部分。
 */
@Component
public class LlmBookAnalysisGenerator implements BookAnalysisGenerator {

    private final BookAnalysisPromptRenderer promptRenderer;
    private final LlmBookAnalysisClient llmClient;
    private final BookAnalysisLlmResultNormalizer resultNormalizer;
    private final BookAnalysisLlmResultParser resultParser;

    public LlmBookAnalysisGenerator(BookAnalysisPromptRenderer promptRenderer,
                                    LlmBookAnalysisClient llmClient,
                                    BookAnalysisLlmResultNormalizer resultNormalizer,
                                    BookAnalysisLlmResultParser resultParser) {
        this.promptRenderer = promptRenderer;
        this.llmClient = llmClient;
        this.resultNormalizer = resultNormalizer;
        this.resultParser = resultParser;
    }

    @Override
    public BookAnalysisTaskResult generate(BookAnalysisTaskInput input) {
        String prompt = promptRenderer.render(input);
        BookAnalysisLlmResult rawResult = llmClient.generate(prompt);
        BookAnalysisLlmResult normalizedResult = resultNormalizer.normalize(input, rawResult);
        return resultParser.parse(input, normalizedResult);
    }
}