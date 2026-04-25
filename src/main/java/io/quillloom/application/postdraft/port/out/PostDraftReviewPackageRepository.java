package io.quillloom.application.postdraft.port.out;

import io.quillloom.domain.postdraft.PostDraftReviewPackage;

import java.util.Optional;

public interface PostDraftReviewPackageRepository {

    Optional<PostDraftReviewPackage> load(String projectId);

    void save(PostDraftReviewPackage reviewPackage);
}
