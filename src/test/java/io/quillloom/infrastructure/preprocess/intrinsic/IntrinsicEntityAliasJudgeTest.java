package io.quillloom.infrastructure.preprocess.intrinsic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntrinsicEntityAliasJudgeTest {

    @Test
    void shouldKeepLowConfidenceAliasAsObservedOnly() {
        IntrinsicEntityAliasJudge judge = new IntrinsicEntityAliasJudge();

        IntrinsicEntityMergeDecision decision = judge.judge("Louki", "Jacqueline", List.of("weak-evidence"));

        assertEquals(IntrinsicAliasState.OBSERVED, decision.state());
    }

    @Test
    void shouldMarkChunkLevelAliasHintAsSuspected() {
        IntrinsicEntityAliasJudge judge = new IntrinsicEntityAliasJudge();

        IntrinsicEntityMergeDecision decision = judge.judge("Louki", "Jacqueline", List.of("same-chunk-alias-hint"));

        assertEquals(IntrinsicAliasState.SUSPECTED_ALIAS, decision.state());
    }

    @Test
    void shouldPromoteExplicitRenameToConfirmedAlias() {
        IntrinsicEntityAliasJudge judge = new IntrinsicEntityAliasJudge();

        IntrinsicEntityMergeDecision decision = judge.judge("Louki", "Jacqueline", List.of("explicit-renaming"));

        assertEquals(IntrinsicAliasState.CONFIRMED_ALIAS, decision.state());
    }
}
