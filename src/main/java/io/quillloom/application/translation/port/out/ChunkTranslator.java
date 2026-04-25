package io.quillloom.application.translation.port.out;

import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationTaskInput;

/**
 * Agent D 的受控执行端口。
 * 当前阶段只负责单轮把稳定执行输入转换为当前 chunk 翻译草稿。
 */
public interface ChunkTranslator {

    ChunkTranslationDraft translate(TranslationTaskInput input);
}
