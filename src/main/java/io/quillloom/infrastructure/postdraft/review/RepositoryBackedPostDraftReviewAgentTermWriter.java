package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.assembler.PostDraftReviewPackageAssembler;
import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentTermWriter;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import io.quillloom.domain.shared.TermTextNormalizer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Component
public class RepositoryBackedPostDraftReviewAgentTermWriter implements PostDraftReviewAgentTermWriter {

    private final PostDraftReviewPackageRepository reviewPackageRepository;
    private final PostDraftReviewPackageAssembler packageAssembler;
    private final RepositoryBackedPostDraftReviewAgentReader reader;

    public RepositoryBackedPostDraftReviewAgentTermWriter(PostDraftReviewPackageRepository reviewPackageRepository,
                                                          PostDraftReviewPackageAssembler packageAssembler,
                                                          RepositoryBackedPostDraftReviewAgentReader reader) {
        this.reviewPackageRepository = Objects.requireNonNull(reviewPackageRepository, "reviewPackageRepository");
        this.packageAssembler = Objects.requireNonNull(packageAssembler, "packageAssembler");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public Map<String, String> recordConfirmedTerms(String projectId, Map<String, String> entries) {
        String normalizedProjectId = requireText(projectId, "projectId");
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }

        PostDraftReviewPackage reviewPackage = reviewPackageRepository.load(normalizedProjectId)
                .orElseThrow(() -> new IllegalStateException("Post-draft review package not found for projectId=" + normalizedProjectId));

        LinkedHashMap<String, ConfirmedTermEntry> mergedConfirmedTerms = confirmedTermEntries(reviewPackage.termState().effectiveConfirmedTerms());
        LinkedHashMap<String, String> applied = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String sourceTerm = requireText(entry.getKey(), "sourceTerm");
            String targetTerm = requireText(entry.getValue(), "targetTerm");
            String sourceKey = TermTextNormalizer.keyText(sourceTerm);
            String targetKey = TermTextNormalizer.keyText(targetTerm);
            ConfirmedTermEntry existing = mergedConfirmedTerms.get(sourceKey);
            if (existing != null && !existing.targetKey().equals(targetKey)) {
                throw new IllegalStateException(
                        "confirmed_term_conflict: sourceTerm=" + sourceTerm
                                + ", existing=" + existing.displayTargetTerm()
                                + ", incoming=" + targetTerm
                );
            }
            if (existing == null) {
                ConfirmedTermEntry next = new ConfirmedTermEntry(sourceTerm, targetTerm, targetKey);
                mergedConfirmedTerms.put(sourceKey, next);
                applied.put(sourceTerm, targetTerm);
            } else {
                applied.put(existing.displaySourceTerm(), existing.displayTargetTerm());
            }
        }

        PostDraftTermState updatedTermState = new PostDraftTermState(
                confirmedTermDisplayMap(mergedConfirmedTerms),
                reviewPackage.termState().effectiveCandidateTerms()
        );
        PostDraftReviewPackage updatedPackage = new PostDraftReviewPackage(
                reviewPackage.projectId(),
                reviewPackage.packageVersion(),
                reviewPackage.sourceLanguage(),
                reviewPackage.targetLanguage(),
                reviewPackage.sourceDocumentDigest(),
                reviewPackage.createdAt(),
                reviewPackage.chunks(),
                reviewPackage.blockIndexes(),
                updatedTermState,
                packageAssembler.buildGlossarySnapshot(updatedTermState),
                reviewPackage.aliasSnapshot(),
                reviewPackage.mergedDraftText()
        );
        reviewPackageRepository.save(updatedPackage);
        reader.invalidateCache(normalizedProjectId);
        return Map.copyOf(applied);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private LinkedHashMap<String, ConfirmedTermEntry> confirmedTermEntries(Map<String, String> confirmedTerms) {
        LinkedHashMap<String, ConfirmedTermEntry> entries = new LinkedHashMap<>();
        if (confirmedTerms == null || confirmedTerms.isEmpty()) {
            return entries;
        }
        confirmedTerms.forEach((sourceTerm, targetTerm) -> {
            String displaySourceTerm = TermTextNormalizer.displayText(sourceTerm);
            String displayTargetTerm = TermTextNormalizer.displayText(targetTerm);
            String sourceKey = TermTextNormalizer.keyText(displaySourceTerm);
            String targetKey = TermTextNormalizer.keyText(displayTargetTerm);
            if (!sourceKey.isBlank() && !targetKey.isBlank()) {
                entries.putIfAbsent(sourceKey, new ConfirmedTermEntry(displaySourceTerm, displayTargetTerm, targetKey));
            }
        });
        return entries;
    }

    private Map<String, String> confirmedTermDisplayMap(Map<String, ConfirmedTermEntry> entries) {
        LinkedHashMap<String, String> display = new LinkedHashMap<>();
        entries.values().forEach(entry -> display.put(entry.displaySourceTerm(), entry.displayTargetTerm()));
        return Map.copyOf(display);
    }

    private record ConfirmedTermEntry(
            String displaySourceTerm,
            String displayTargetTerm,
            String targetKey
    ) {
    }
}
