package io.quillloom.infrastructure.preprocess.bookanalysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalConstraintBoundaryJudgeTest {

    @Test
    void shouldRejectEntityLevelDoNotTranslateRules() {
        GlobalConstraintBoundaryJudge judge = new GlobalConstraintBoundaryJudge();

        GlobalConstraintBoundaryDecision decision = judge.judge(
                "consistency",
                "所有专有名词保留法语原文不译，仅首次出现时加中文注释"
        );

        assertFalse(decision.accepted());
        assertEquals("entity-level-do-not-translate", decision.reasonCode());
    }

    @Test
    void shouldAcceptStableProjectLevelNamingPrinciple() {
        GlobalConstraintBoundaryJudge judge = new GlobalConstraintBoundaryJudge();

        GlobalConstraintBoundaryDecision decision = judge.judge(
                "consistency",
                "全书命名应保持一致，未确认译名不要在不同 chunk 之间随意漂移"
        );

        assertTrue(decision.accepted());
    }
}
