package io.quillloom.application.postdraft.review.model;

import java.util.List;

public record TranscriptStore(
        List<String> entries,
        boolean flushed
) {

    public TranscriptStore {
        entries = normalizeEntries(entries);
    }

    public static TranscriptStore empty() {
        return new TranscriptStore(List.of(), false);
    }

    @Override
    public List<String> entries() {
        return List.copyOf(entries);
    }

    public TranscriptStore append(String entry) {
        String normalized = normalizeEntry(entry);
        if (normalized == null) {
            return this;
        }
        java.util.ArrayList<String> updated = new java.util.ArrayList<>(entries);
        updated.add(normalized);
        return new TranscriptStore(updated, false);
    }

    public TranscriptStore compact(int keepLast) {
        if (keepLast < 0) {
            throw new IllegalArgumentException("keepLast must be >= 0");
        }
        if (entries.size() <= keepLast) {
            return this;
        }
        return new TranscriptStore(entries.subList(entries.size() - keepLast, entries.size()), flushed);
    }

    public TranscriptStore prepend(String entry) {
        String normalized = normalizeEntry(entry);
        if (normalized == null) {
            return this;
        }
        java.util.ArrayList<String> updated = new java.util.ArrayList<>();
        updated.add(normalized);
        updated.addAll(entries);
        return new TranscriptStore(updated, false);
    }

    public List<String> replay() {
        return List.copyOf(entries);
    }

    public TranscriptStore flush() {
        if (flushed) {
            return this;
        }
        return new TranscriptStore(entries, true);
    }

    private static List<String> normalizeEntries(List<String> entries) {
        if (entries == null) {
            return List.of();
        }
        java.util.ArrayList<String> normalized = new java.util.ArrayList<>();
        for (String entry : entries) {
            String normalizedEntry = normalizeEntry(entry);
            if (normalizedEntry != null) {
                normalized.add(normalizedEntry);
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalizeEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        return entry.trim();
    }
}
