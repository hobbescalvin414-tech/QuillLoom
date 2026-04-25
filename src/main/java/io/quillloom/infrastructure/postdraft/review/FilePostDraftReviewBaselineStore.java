package io.quillloom.infrastructure.postdraft.review;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewBaselineStore;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class FilePostDraftReviewBaselineStore implements PostDraftReviewBaselineStore {

    private final Path rootDirectory;
    private final PostDraftReviewPackageRepository reviewPackageRepository;
    private final ObjectMapper objectMapper;

    public FilePostDraftReviewBaselineStore(Path rootDirectory,
                                            PostDraftReviewPackageRepository reviewPackageRepository,
                                            ObjectMapper objectMapper) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
        this.reviewPackageRepository = Objects.requireNonNull(reviewPackageRepository, "reviewPackageRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .findAndRegisterModules()
                .disable(MapperFeature.AUTO_DETECT_IS_GETTERS);
    }

    @Override
    public void createBaseline(String projectId) {
        String normalizedProjectId = requireText(projectId, "projectId");
        PostDraftReviewPackage reviewPackage = reviewPackageRepository.load(normalizedProjectId)
                .orElseThrow(() -> new IllegalStateException("Post-draft review package not found for projectId=" + normalizedProjectId));
        try {
            Files.createDirectories(rootDirectory);
            Files.writeString(targetPath(normalizedProjectId), objectMapper.writeValueAsString(reviewPackage));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create review baseline for projectId=" + normalizedProjectId, ex);
        }
    }

    @Override
    public void restoreBaseline(String projectId) {
        String normalizedProjectId = requireText(projectId, "projectId");
        Path target = targetPath(normalizedProjectId);
        if (!Files.exists(target)) {
            throw new IllegalStateException("Review baseline not found for projectId=" + normalizedProjectId);
        }
        try {
            PostDraftReviewPackage reviewPackage = objectMapper.readValue(Files.readString(target), PostDraftReviewPackage.class);
            reviewPackageRepository.save(reviewPackage);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to restore review baseline for projectId=" + normalizedProjectId, ex);
        }
    }

    private Path targetPath(String projectId) {
        return rootDirectory.resolve(projectId + ".json");
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
