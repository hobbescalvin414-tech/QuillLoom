package io.quillloom.domain.preprocess;

import java.util.List;

/**
 * 当前 chunk 内部关于“多个称呼可能指向同一人物”的弱提示。
 * 仅供翻译阶段参考，不代表已确认事实，也不应直接写入长期记忆。
 */
public record PersonAliasHint(
        List<String> surfaceForms,
        String hintType,
        String confidence,
        String evidence
) {

    public PersonAliasHint {
        surfaceForms = surfaceForms == null ? List.of() : List.copyOf(surfaceForms);
        hintType = hintType == null ? "" : hintType;
        confidence = confidence == null ? "" : confidence;
        evidence = evidence == null ? "" : evidence;
    }
}
