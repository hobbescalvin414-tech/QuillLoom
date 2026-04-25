package io.quillloom.infrastructure.preprocess;

import java.util.List;

/**
 * C0 的外部搜索客户端端口。
 * 当前约定外部服务按单次查询返回若干命中结果。
 */
public interface KnowledgeSearchClient {

    List<KnowledgeSearchHit> search(KnowledgeSearchQuery query);
}