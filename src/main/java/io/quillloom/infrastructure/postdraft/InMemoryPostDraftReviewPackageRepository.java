package io.quillloom.infrastructure.postdraft;

import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "quillloom.post-draft-review-package", name = "storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryPostDraftReviewPackageRepository implements PostDraftReviewPackageRepository {

    private final Map<String, PostDraftReviewPackage> storage = new ConcurrentHashMap<>();

    @Override
    public Optional<PostDraftReviewPackage> load(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(storage.get(projectId));
    }

    @Override
    public void save(PostDraftReviewPackage reviewPackage) {
        if (reviewPackage == null || reviewPackage.projectId() == null || reviewPackage.projectId().isBlank()) {
            throw new IllegalArgumentException("reviewPackage must have a projectId.");
        }
        storage.put(reviewPackage.projectId(), reviewPackage);
    }
}
