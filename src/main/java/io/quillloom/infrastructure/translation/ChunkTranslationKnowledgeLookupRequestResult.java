package io.quillloom.infrastructure.translation;

import java.util.List;

/**
 * Agent D 第 1 轮可显式输出的补卡请求块，只在运行期使用。
 */
public record ChunkTranslationKnowledgeLookupRequestResult(
        String reason,
        List<String> queryTerms,
        List<String> requestedTypes,
        List<String> anchors,
        Integer limit
) {
}