package io.quillloom.infrastructure.preprocess.intrinsic;

public record IntrinsicEntityMergeDecision(
        IntrinsicAliasState state
) {

    public static IntrinsicEntityMergeDecision observed() {
        return new IntrinsicEntityMergeDecision(IntrinsicAliasState.OBSERVED);
    }

    public static IntrinsicEntityMergeDecision suspected() {
        return new IntrinsicEntityMergeDecision(IntrinsicAliasState.SUSPECTED_ALIAS);
    }

    public static IntrinsicEntityMergeDecision confirmed() {
        return new IntrinsicEntityMergeDecision(IntrinsicAliasState.CONFIRMED_ALIAS);
    }
}
