package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.port.out.HumanInTheLoopGateway;

import java.util.Objects;

public class InMemoryHumanInTheLoopGateway implements HumanInTheLoopGateway {

    @Override
    public HumanReviewRequest submit(HumanReviewRequest request) {
        return Objects.requireNonNull(request, "request");
    }
}
