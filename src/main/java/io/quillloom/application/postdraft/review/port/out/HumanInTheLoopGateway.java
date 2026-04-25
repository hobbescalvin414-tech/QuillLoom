package io.quillloom.application.postdraft.review.port.out;

import io.quillloom.application.postdraft.review.model.HumanReviewRequest;

public interface HumanInTheLoopGateway {

    HumanReviewRequest submit(HumanReviewRequest request);
}
