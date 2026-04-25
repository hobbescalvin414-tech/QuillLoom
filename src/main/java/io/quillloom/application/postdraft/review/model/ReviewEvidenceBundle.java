package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public record ReviewEvidenceBundle(
        List<String> readContextSummaries,
        List<String> evidenceSummaries,
        List<String> keyEvidenceSummaries,
        List<String> conflictingEvidenceSummaries,
        List<String> evidenceGaps
) {

    public ReviewEvidenceBundle {
        readContextSummaries = normalize(readContextSummaries);
        evidenceSummaries = normalize(evidenceSummaries);
        keyEvidenceSummaries = normalize(keyEvidenceSummaries);
        conflictingEvidenceSummaries = normalize(conflictingEvidenceSummaries);
        evidenceGaps = normalize(evidenceGaps);
    }

    public static ReviewEvidenceBundle empty() {
        return new ReviewEvidenceBundle(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public static ReviewEvidenceBundle fromLegacy(List<String> readContextSummaries,
                                                  List<String> evidenceSummaries,
                                                  List<String> keyEvidenceSummaries,
                                                  List<String> conflictingEvidenceSummaries,
                                                  List<String> evidenceGaps) {
        return new ReviewEvidenceBundle(
                readContextSummaries,
                evidenceSummaries,
                keyEvidenceSummaries,
                conflictingEvidenceSummaries,
                evidenceGaps
        );
    }

    public ReviewEvidenceBundle merge(ReviewEvidenceBundle other) {
        if (other == null) {
            return this;
        }
        return new ReviewEvidenceBundle(
                merge(readContextSummaries, other.readContextSummaries),
                merge(evidenceSummaries, other.evidenceSummaries),
                merge(keyEvidenceSummaries, other.keyEvidenceSummaries),
                merge(conflictingEvidenceSummaries, other.conflictingEvidenceSummaries),
                merge(evidenceGaps, other.evidenceGaps)
        );
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        ArrayList<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }

    public ReviewEvidenceBundle compact(int maxEntries) {
        if (maxEntries < 0) {
            throw new IllegalArgumentException("maxEntries must be >= 0");
        }
        return new ReviewEvidenceBundle(
                compactList(readContextSummaries, maxEntries, "readContext"),
                compactList(evidenceSummaries, maxEntries, "evidence"),
                compactList(keyEvidenceSummaries, maxEntries, "keyEvidence"),
                compactList(conflictingEvidenceSummaries, maxEntries, "conflicting"),
                compactList(evidenceGaps, maxEntries, "gaps")
        );
    }

    public int totalEntries() {
        return readContextSummaries.size()
                + evidenceSummaries.size()
                + keyEvidenceSummaries.size()
                + conflictingEvidenceSummaries.size()
                + evidenceGaps.size();
    }

    private static List<String> compactList(List<String> entries, int maxEntries, String label) {
        if (entries.size() <= maxEntries) {
            return entries;
        }
        int dropped = entries.size() - maxEntries;
        String summary = "[compact:%s] 已压缩 %d 条旧证据，保留最近 %d 条".formatted(label, dropped, maxEntries);
        List<String> kept = entries.subList(entries.size() - maxEntries, entries.size());
        ArrayList<String> result = new ArrayList<>();
        result.add(summary);
        result.addAll(kept);
        return List.copyOf(result);
    }

    private static List<String> merge(List<String> left, List<String> right) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(left);
        merged.addAll(right);
        return List.copyOf(merged);
    }
}
