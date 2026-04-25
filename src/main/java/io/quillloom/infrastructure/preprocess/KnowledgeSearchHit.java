package io.quillloom.infrastructure.preprocess;

import java.util.List;

/**
 * 外部搜索服务返回的单条命中结果。
 */
public record KnowledgeSearchHit(
        String title,
        String snippet,
        String url,
        String source,
        List<String> keywords
) {
}