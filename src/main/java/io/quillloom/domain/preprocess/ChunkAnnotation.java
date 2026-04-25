package io.quillloom.domain.preprocess;

import java.util.List;

/**
 * Structured chunk-level preprocessing output.
 * - chunk 从文本片段提升为结构化对象
 * - 后续系统里的 chunk 不再只是字符串
 * 包含ChunkDescriptor 基本信息和产生的问题、背景问题、风险点。关键表达
 */
public record ChunkAnnotation(
        ChunkDescriptor chunk,
        String summary,
        List<String> entities,
        List<String> backgroundQuestions,
        List<String> translationRisks,
        List<String> keyExpressions,
        List<PersonAliasHint> personAliasHints
) {

    public ChunkAnnotation {
        personAliasHints = personAliasHints == null ? List.of() : List.copyOf(personAliasHints);
    }

    public ChunkAnnotation(ChunkDescriptor chunk,
                           String summary,
                           List<String> entities,
                           List<String> backgroundQuestions,
                           List<String> translationRisks,
                           List<String> keyExpressions) {
        this(chunk, summary, entities, backgroundQuestions, translationRisks, keyExpressions, List.of());
    }
}
