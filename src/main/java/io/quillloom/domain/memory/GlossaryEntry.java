package io.quillloom.domain.memory;

import java.util.List;

public record GlossaryEntry(
        String sourceTerm,
        String targetTerm,
        GlossaryEntryStrength entryStrength,
        GlossaryEntrySourceKind sourceKind,
        List<String> evidenceRefs,
        String notes
) {

    public GlossaryEntry {
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        notes = notes == null ? "" : notes;
    }
}
