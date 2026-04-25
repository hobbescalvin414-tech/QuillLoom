package io.quillloom.domain.knowledge;

/**
 * 影响后续翻译决策的全局约束。
 */
public record GlobalConstraint(
        String constraintId,
        String type,
        String description
) {
}