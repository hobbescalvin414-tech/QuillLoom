package io.quillloom.application.translation.port.out;

import io.quillloom.application.translation.runtime.KnowledgeCardLookupRequest;
import io.quillloom.application.translation.runtime.KnowledgeCardLookupResponse;
import io.quillloom.domain.translation.TranslationTaskInput;

/**
 * D 在单 chunk loop 内查询本地知识库的端口。
 */
public interface LocalKnowledgeLookupService {

    KnowledgeCardLookupResponse lookup(TranslationTaskInput input,
                                       KnowledgeCardLookupRequest request);
}