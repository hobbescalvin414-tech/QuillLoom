package io.quillloom.application.translation.assembler;

import io.quillloom.domain.translation.DraftCompilation;
import io.quillloom.domain.translation.DraftCompilationInput;
import io.quillloom.domain.translation.TranslationDecisionNote;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent E 的最小拼接器。
 * 当前只负责顺序拼接、保留问题记录和轻量边界整理，不承担重译职责。
 */
@Component
public class DraftCompilationAssembler {

    public DraftCompilation assemble(DraftCompilationInput input) {
        List<TranslationDecisionNote> carriedDecisionNotes = input.chunkDrafts().stream()
                .flatMap(draft -> draft.decisionNotes().stream())
                .toList();

        String mergedDraft = input.chunkDrafts().stream()
                .map(draft -> draft.translatedText().trim())
                .reduce((left, right) -> left + System.lineSeparator() + System.lineSeparator() + right)
                .orElse("");

        return new DraftCompilation(
                input.projectId(),
                input.chunkDrafts(),
                mergedDraft,
                carriedDecisionNotes
        );
    }
}