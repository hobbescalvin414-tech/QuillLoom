package io.quillloom.infrastructure.preprocess.bookanalysis;

import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import io.quillloom.application.preprocess.model.BookAnalysisTaskResult;
import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.preprocess.BookAnalysis;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将规范化后的 LLM 返回结果转换为 Agent A 的稳定执行结果契约。
 */
@Component
public class BookAnalysisLlmResultParser {

    private final GlobalConstraintBoundaryJudge boundaryJudge;

    public BookAnalysisLlmResultParser() {
        this(new GlobalConstraintBoundaryJudge());
    }

    public BookAnalysisLlmResultParser(GlobalConstraintBoundaryJudge boundaryJudge) {
        this.boundaryJudge = boundaryJudge;
    }

    public BookAnalysisTaskResult parse(BookAnalysisTaskInput input, BookAnalysisLlmResult result) {
        BookAnalysis bookAnalysis = new BookAnalysis(
                result.synopsis(),
                result.narrativeOutline(),
                result.styleProfile(),
                result.globalRisks(),
                result.translationStrategyNotes()
        );

        List<GlobalConstraint> constraints = new ArrayList<>();
        int sequence = 1;
        for (BookAnalysisLlmConstraint constraint : result.globalConstraints()) {
            if (constraint == null || !boundaryJudge.judge(constraint.type(), constraint.description()).accepted()) {
                continue;
            }
            constraints.add(new GlobalConstraint(
                    "book-analysis-constraint-" + sequence,
                    constraint.type(),
                    constraint.description()
            ));
            sequence++;
        }
        if (constraints.isEmpty()) {
            constraints.add(new GlobalConstraint(
                    "book-analysis-constraint-1",
                    "general",
                    "保持全书术语、命名和叙述语体的一致性。"
            ));
        }

        return new BookAnalysisTaskResult(
                bookAnalysis,
                List.copyOf(constraints),
                Map.of(
                        "acceptedGlobalConstraints", result.globalConstraints().stream()
                                .map(constraint -> Map.of(
                                        "type", constraint.type(),
                                        "description", constraint.description()
                                ))
                                .toList(),
                        "rejectedGlobalConstraints", result.rejectedGlobalConstraints().stream()
                                .map(rejected -> Map.of(
                                        "type", rejected.type(),
                                        "description", rejected.description(),
                                        "reasonCode", rejected.reasonCode()
                                ))
                                .toList()
                )
        );
    }
}
