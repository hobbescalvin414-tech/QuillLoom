package io.quillloom.infrastructure.translation;

/**
 * 正文边界问题。用于把规则检测结果显式交给第 2 轮 LLM 修订。
 */
public record TranslatedTextIssue(
        String code,
        String description
) {
}
