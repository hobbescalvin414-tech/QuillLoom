package io.quillloom.domain.memory;

import java.util.List;
import java.util.Map;

public record DraftStageGlobalGlossary(
        List<GlossaryEntry> hardEntries,
        List<GlossaryEntry> softEntries,
        Map<String, Object> coverageSummary
) {

    public DraftStageGlobalGlossary {
        hardEntries = hardEntries == null ? List.of() : List.copyOf(hardEntries);
        softEntries = softEntries == null ? List.of() : List.copyOf(softEntries);
        coverageSummary = coverageSummary == null ? Map.of() : Map.copyOf(coverageSummary);
    }

    public static DraftStageGlobalGlossary empty() {
        return new DraftStageGlobalGlossary(List.of(), List.of(), Map.of());
    }
}
