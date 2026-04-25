package io.quillloom.application.postdraft.review.port.out;

import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermsProposal;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;

public interface ReviewAgentStructuredGenerationPort {

    ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt);

    default RecordConfirmedTermsProposal generateRecordConfirmedTermsProposal(String systemPrompt, String userPrompt) {
        throw new UnsupportedOperationException("generateRecordConfirmedTermsProposal");
    }

    ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt);

    RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt);

    RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt);
}
