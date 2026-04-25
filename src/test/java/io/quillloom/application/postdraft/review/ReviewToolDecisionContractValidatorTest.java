package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolDefinition;
import io.quillloom.application.postdraft.review.model.ToolArgumentSchema;
import io.quillloom.application.postdraft.review.service.ReviewToolDecisionContractValidator;
import io.quillloom.application.postdraft.review.service.ReviewToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewToolDecisionContractValidatorTest {

    @Test
    void shouldRejectRecordConfirmedTermsEntriesWhenEntriesIsArrayOfObjects() {
        ReviewToolDecision decision = new ReviewToolDecision(
                "record_confirmed_terms",
                Map.of("entries", List.of(Map.of("sourceTerm", "Bernolle", "targetTerm", "Bernolle translated"))),
                "record"
        );

        assertEquals(
                Optional.of("invalid_argument:entries"),
                new ReviewToolDecisionContractValidator().validate(decision, ReviewToolRegistry.defaultRegistry())
        );
    }

    @Test
    void shouldRejectRecordConfirmedTermsEntriesWhenUsingSourceTermTargetTermPairObject() {
        ReviewToolDecision decision = new ReviewToolDecision(
                "record_confirmed_terms",
                Map.of("entries", Map.of("sourceTerm", "Bernolle", "targetTerm", "Bernolle translated")),
                "record"
        );

        assertEquals(
                Optional.of("invalid_argument:entries"),
                new ReviewToolDecisionContractValidator().validate(decision, ReviewToolRegistry.defaultRegistry())
        );
    }

    @Test
    void shouldRejectRecordConfirmedTermsEntriesWhenEntriesIsArrayOfStrings() {
        ReviewToolDecision decision = new ReviewToolDecision(
                "record_confirmed_terms",
                Map.of("entries", List.of("Bernolle=Bernolle translated")),
                "record"
        );

        assertEquals(
                Optional.of("invalid_argument:entries"),
                new ReviewToolDecisionContractValidator().validate(decision, ReviewToolRegistry.defaultRegistry())
        );
    }

    @Test
    void shouldRejectRecordConfirmedTermsEntriesWhenEntriesIsEmptyObject() {
        ReviewToolDecision decision = new ReviewToolDecision(
                "record_confirmed_terms",
                Map.of("entries", Map.of()),
                "record"
        );

        assertEquals(
                Optional.of("invalid_argument:entries"),
                new ReviewToolDecisionContractValidator().validate(decision, ReviewToolRegistry.defaultRegistry())
        );
    }

    @Test
    void shouldAcceptRecordConfirmedTermsEntriesWhenEntriesIsStringMap() {
        ReviewToolDecision decision = new ReviewToolDecision(
                "record_confirmed_terms",
                Map.of("entries", Map.of("Bernolle", "Bernolle translated")),
                "record"
        );

        assertEquals(
                Optional.empty(),
                new ReviewToolDecisionContractValidator().validate(decision, ReviewToolRegistry.defaultRegistry())
        );
    }

    @Test
    void shouldRejectUnexpectedArgumentBasedOnToolDefinition() {
        ReviewToolDecision decision = new ReviewToolDecision(
                "read_confirmed_terms",
                Map.of("queryTerms", List.of("Le Conde")),
                "lookup"
        );

        assertEquals(
                Optional.of("unexpected_argument:queryTerms"),
                new ReviewToolDecisionContractValidator().validate(decision, ReviewToolRegistry.defaultRegistry())
        );
    }

    @Test
    void shouldRequireTopLevelReasonForHumanReviewAndEmptyArguments() {
        ReviewToolDecision decision = new ReviewToolDecision(
                "request_human_review",
                Map.of("reason", "help"),
                ""
        );

        assertEquals(
                Optional.of("unexpected_argument:reason"),
                new ReviewToolDecisionContractValidator().validate(decision, ReviewToolRegistry.defaultRegistry())
        );
    }

    @Test
    void shouldValidateEntriesUsingRegistryDeclaredArgumentType() {
        ReviewToolRegistry registry = new ReviewToolRegistry(List.of(
                ReviewToolDefinition.builder("record_confirmed_terms", "record confirmed terms")
                        .whenToUse("use")
                        .whenNotToUse("avoid")
                        .resultSemantics("result")
                        .repeatPolicy(io.quillloom.application.postdraft.review.model.ToolRepeatPolicy.STATE_TRANSITION_ONLY)
                        .nextStepGuidance("next")
                        .requiredArguments(Set.of("entries"))
                        .argumentSchemas(List.of(
                                new ToolArgumentSchema(
                                        "entries",
                                        "object{string:string}",
                                        true,
                                        "custom map"
                                )
                        ))
                        .build()
        ));

        ReviewToolDecision invalidDecision = new ReviewToolDecision(
                "record_confirmed_terms",
                Map.of("entries", List.of(Map.of("sourceTerm", "Bernolle", "targetTerm", "Bernolle translated"))),
                "record"
        );

        assertEquals(
                Optional.of("invalid_argument:entries"),
                new ReviewToolDecisionContractValidator().validate(invalidDecision, registry)
        );
    }
}
