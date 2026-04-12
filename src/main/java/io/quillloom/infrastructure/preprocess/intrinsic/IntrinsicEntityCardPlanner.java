package io.quillloom.infrastructure.preprocess.intrinsic;

import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.PersonAliasHint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class IntrinsicEntityCardPlanner {

    public List<IntrinsicEntityCardDraft> plan(List<ChunkAnnotation> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<IntrinsicEntityCardDraft> drafts = new ArrayList<>();
        for (ChunkAnnotation chunk : chunks) {
            for (PersonAliasHint aliasHint : safeValues(chunk.personAliasHints())) {
                if (!isUsableAliasHint(aliasHint)) {
                    continue;
                }
                List<String> orderedForms = aliasHint.surfaceForms().stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
                if (orderedForms.size() < 2) {
                    continue;
                }

                String canonicalName = chooseCanonicalName(orderedForms, safeList(chunk.entities()));
                Set<String> aliases = new LinkedHashSet<>(orderedForms);
                aliases.remove(canonicalName);

                Set<String> surfaceForms = new LinkedHashSet<>();
                surfaceForms.addAll(orderedForms);
                surfaceForms.addAll(safeList(chunk.entities()));

                List<String> evidenceChunks = collectEvidenceChunks(chunks, surfaceForms);
                String firstSeenChunkId = findFirstSeenChunkId(chunks, surfaceForms);
                if (firstSeenChunkId == null) {
                    firstSeenChunkId = chunk.chunk().chunkId();
                }

                drafts.add(new IntrinsicEntityCardDraft(
                        canonicalName,
                        Set.copyOf(aliases),
                        Set.copyOf(surfaceForms),
                        List.copyOf(evidenceChunks),
                        firstSeenChunkId,
                        chunk.summary() == null ? "" : chunk.summary(),
                        IntrinsicAliasState.SUSPECTED_ALIAS,
                        normalizeConfidence(aliasHint.confidence())
                ));
            }
        }
        return List.copyOf(drafts);
    }

    private boolean isUsableAliasHint(PersonAliasHint hint) {
        if (hint == null) {
            return false;
        }
        return "same-person-name-variant".equalsIgnoreCase(hint.hintType())
                && hint.surfaceForms() != null
                && hint.surfaceForms().size() >= 2;
    }

    private String chooseCanonicalName(List<String> orderedForms, List<String> entities) {
        for (String entity : entities) {
            if (orderedForms.contains(entity)) {
                return entity;
            }
        }
        return orderedForms.get(0);
    }

    private List<String> collectEvidenceChunks(List<ChunkAnnotation> chunks, Set<String> surfaceForms) {
        List<String> evidence = new ArrayList<>();
        for (ChunkAnnotation chunk : chunks) {
            if (containsAny(surfaceForms, safeList(chunk.entities()))) {
                evidence.add(chunk.chunk().chunkId());
            }
        }
        return List.copyOf(evidence);
    }

    private String findFirstSeenChunkId(List<ChunkAnnotation> chunks, Set<String> surfaceForms) {
        for (ChunkAnnotation chunk : chunks) {
            if (containsAny(surfaceForms, safeList(chunk.entities()))) {
                return chunk.chunk().chunkId();
            }
        }
        return null;
    }

    private boolean containsAny(Set<String> surfaceForms, List<String> entities) {
        for (String entity : entities) {
            if (surfaceForms.contains(entity)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeConfidence(String confidence) {
        if (confidence == null || confidence.isBlank()) {
            return "medium";
        }
        return confidence.trim().toLowerCase();
    }

    private <T> List<T> safeValues(List<T> values) {
        return values == null ? List.of() : values;
    }

    private List<String> safeList(List<String> values) {
        return safeValues(values);
    }
}
