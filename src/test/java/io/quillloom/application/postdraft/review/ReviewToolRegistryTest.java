package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolDefinition;
import io.quillloom.application.postdraft.review.model.ToolArgumentSchema;
import io.quillloom.application.postdraft.review.model.ToolRepeatPolicy;
import io.quillloom.application.postdraft.review.service.ReviewToolDecisionContractValidator;
import io.quillloom.application.postdraft.review.service.ReviewToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewToolRegistryTest {

    @Test
    void shouldExposeCompleteWorkingSetTool() {
        ReviewToolRegistry registry = ReviewToolRegistry.defaultRegistry();

        ReviewToolDefinition tool = registry.require("complete_working_set");

        assertEquals("complete_working_set", tool.toolName());
        assertTrue(tool.description().contains("chunk"));
    }

    @Test
    void shouldDefineFormalContractForEveryDefaultTool() {
        ReviewToolRegistry registry = ReviewToolRegistry.defaultRegistry();

        assertEquals(13, registry.definitions().size());
        for (ReviewToolDefinition definition : registry.definitions()) {
            assertFalse(definition.toolName().isBlank());
            assertFalse(definition.description().isBlank());
            assertFalse(definition.whenToUse().isBlank(), definition.toolName());
            assertFalse(definition.whenNotToUse().isBlank(), definition.toolName());
            assertFalse(definition.resultSemantics().isBlank(), definition.toolName());
            assertNotNull(definition.repeatPolicy(), definition.toolName());
            assertFalse(definition.nextStepGuidance().isBlank(), definition.toolName());
        }
    }

    @Test
    void shouldMarkReadConfirmedTermsAsAuthoritativeAndNonRepeatableAfterSuccess() {
        ReviewToolDefinition tool = ReviewToolRegistry.defaultRegistry().require("read_confirmed_terms");

        assertTrue(tool.authoritativeResult());
        assertEquals(ToolRepeatPolicy.FORBID_SAME_SIGNATURE_AFTER_SUCCESS, tool.repeatPolicy());
        assertTrue(tool.whenNotToUse().contains("sourceTerm"));
        assertTrue(tool.whenNotToUse().contains("hit"));
        assertTrue(tool.whenNotToUse().contains("miss"));
        assertTrue(tool.nextStepGuidance().contains("complete_working_set"));
        assertTrue(tool.nextStepGuidance().contains("evaluate_focus"));
    }

    @Test
    void shouldBuildToolDefinitionWithNamedBuilderFields() {
        ReviewToolDefinition tool = ReviewToolDefinition.builder("read_confirmed_terms", "lookup confirmed terms")
                .whenToUse("use when current focus contains a source term")
                .whenNotToUse("do not prefetch unrelated terms or repeat the same lookup")
                .resultSemantics("hit and miss are both authoritative query results")
                .repeatPolicy(ToolRepeatPolicy.FORBID_SAME_SIGNATURE_AFTER_SUCCESS)
                .authoritativeResult(true)
                .nextStepGuidance("after lookup evaluate whether current translation is consistent")
                .requiredArguments(java.util.Set.of("sourceTerms"))
                .argumentSchemas(List.of(new ToolArgumentSchema("sourceTerms", "string[]", true, "source terms to look up")))
                .build();

        assertEquals("read_confirmed_terms", tool.toolName());
        assertEquals(ToolRepeatPolicy.FORBID_SAME_SIGNATURE_AFTER_SUCCESS, tool.repeatPolicy());
        assertTrue(tool.authoritativeResult());
    }

    @Test
    void shouldRejectUnknownToolName() {
        ReviewToolRegistry registry = ReviewToolRegistry.defaultRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.require("unknown_tool"));
    }

    @Test
    void shouldRenderPerToolArgumentExamples() {
        ReviewToolRegistry registry = ReviewToolRegistry.defaultRegistry();

        assertEquals("{}", registry.require("draft_revision").renderArgumentsExample());
        assertEquals("{}", registry.require("evaluate_focus").renderArgumentsExample());

        String readConfirmedTermsExample = registry.require("read_confirmed_terms").renderArgumentsExample();
        assertTrue(readConfirmedTermsExample.contains("sourceTerms"));
        assertFalse(readConfirmedTermsExample.contains("chunkIds"));
        assertFalse(readConfirmedTermsExample.contains("entries"));
        assertFalse(readConfirmedTermsExample.contains("count"));

        String completeWorkingSetExample = registry.require("complete_working_set").renderArgumentsExample();
        assertTrue(completeWorkingSetExample.contains("chunkIds"));
        assertFalse(completeWorkingSetExample.contains("sourceTerms"));
        assertFalse(completeWorkingSetExample.contains("entries"));
        assertFalse(completeWorkingSetExample.contains("count"));

        String recordConfirmedTermsExample = registry.require("record_confirmed_terms").renderArgumentsExample();
        assertTrue(recordConfirmedTermsExample.contains("\"entries\""));
        assertTrue(recordConfirmedTermsExample.contains("\"Bernolle\""));
        assertTrue(recordConfirmedTermsExample.contains("\"Bernolle CN\""));
        assertFalse(recordConfirmedTermsExample.contains("sourceTerm"));
        assertFalse(recordConfirmedTermsExample.contains("targetTerm"));
    }

    @Test
    void shouldClarifyRecordConfirmedTermsDoesNotCompleteCurrentChunk() {
        ReviewToolDefinition tool = ReviewToolRegistry.defaultRegistry().require("record_confirmed_terms");
        String description = tool.description();
        String argumentRequirements = tool.renderArgumentRequirements();

        assertTrue(description.contains("chunk"));
        assertTrue(description.contains("chunk") || description.contains("confirmed"));
        assertTrue(argumentRequirements.contains("object{string:string}"));
        assertTrue(argumentRequirements.contains("entries"));
        assertTrue(argumentRequirements.contains("<source-term>"));
        assertTrue(tool.whenToUse().contains("stable source->target pair"));
        assertFalse(tool.whenNotToUse().contains("backfill draft-stage omissions"));
    }

    @Test
    void shouldClarifyLowPrioritySignalsCannotAuthorizeHighRiskActionsByThemselves() {
        ReviewToolRegistry registry = ReviewToolRegistry.defaultRegistry();

        ReviewToolDefinition draftRevision = registry.require("draft_revision");
        ReviewToolDefinition humanReview = registry.require("request_human_review");
        ReviewToolDefinition readConfirmedTerms = registry.require("read_confirmed_terms");

        assertTrue(draftRevision.whenNotToUse().contains("decisionNotes"));
        assertTrue(draftRevision.whenNotToUse().contains("translatorCommentary"));
        assertTrue(draftRevision.whenNotToUse().contains("transitionNote"));
        assertTrue(draftRevision.whenNotToUse().contains("confirmedTermLookupMiss"));
        assertTrue(humanReview.whenNotToUse().contains("decisionNotes"));
        assertTrue(humanReview.whenNotToUse().contains("translatorCommentary"));
        assertTrue(humanReview.whenNotToUse().contains("transitionNote"));
        assertTrue(humanReview.whenNotToUse().contains("confirmedTermLookupMiss"));
        assertTrue(readConfirmedTerms.resultSemantics().contains("未登记事实"));
        assertTrue(readConfirmedTerms.resultSemantics().contains("不表示允许登记"));
    }

    @Test
    void shouldUseTopLevelReasonForHumanReviewRequest() {
        ReviewToolDefinition tool = ReviewToolRegistry.defaultRegistry().require("request_human_review");

        assertEquals("{}", tool.renderArgumentsExample());

        ReviewToolDecisionContractValidator validator = new ReviewToolDecisionContractValidator();
        ReviewToolDecision decision = new ReviewToolDecision(
                "request_human_review",
                Map.of(),
                "need human judgment"
        );

        assertTrue(validator.validate(decision, ReviewToolRegistry.defaultRegistry()).isEmpty());
    }

    @Test
    void shouldClarifyCompleteWorkingSetOnlySubmitsPendingChunks() {
        ReviewToolDefinition tool = ReviewToolRegistry.defaultRegistry().require("complete_working_set");

        assertTrue(tool.whenNotToUse().contains("pending"));
        assertTrue(tool.whenNotToUse().contains("chunk"));
        assertTrue(tool.nextStepGuidance().contains("complete_project"));
    }

    @Test
    void shouldClarifyPendingEmptyAndStaleFocusEndgameGuidance() {
        ReviewToolDefinition completeWorkingSet = ReviewToolRegistry.defaultRegistry().require("complete_working_set");
        ReviewToolDefinition completeProject = ReviewToolRegistry.defaultRegistry().require("complete_project");

        assertTrue(completeWorkingSet.nextStepGuidance().contains("focusChunk is no longer pending"));
        assertTrue(completeWorkingSet.nextStepGuidance().contains("complete_project"));
        assertTrue(completeProject.whenToUse().contains("pending chunks are empty"));
        assertTrue(completeProject.nextStepGuidance().contains("project close-out"));
    }

    @Test
    void shouldClarifyContextOnlyAdjacentChunksDoNotBlockAnchorCompletion() {
        ReviewToolDefinition tool = ReviewToolRegistry.defaultRegistry().require("complete_working_set");

        assertTrue(tool.whenToUse().contains("anchor chunk is ready to submit"));
        assertTrue(tool.nextStepGuidance().contains("Adjacent chunks read only as context evidence do not automatically become required chunkIds"));
        assertTrue(tool.nextStepGuidance().contains("the current focus anchor may still be completed on its own"));
    }

    @Test
    void shouldRejectArgumentsThatDoNotBelongToSelectedTool() {
        ReviewToolDecisionContractValidator validator = new ReviewToolDecisionContractValidator();
        ReviewToolDecision decision = new ReviewToolDecision(
                "read_confirmed_terms",
                Map.of("sourceTerms", List.of("Le Conde"), "chunkIds", List.of("chunk-4")),
                "lookup term"
        );

        assertEquals(
                "unexpected_argument:chunkIds",
                validator.validate(decision, ReviewToolRegistry.defaultRegistry()).orElseThrow()
        );
    }

    @Test
    void shouldRejectArgumentsForNoArgumentTool() {
        ReviewToolDecisionContractValidator validator = new ReviewToolDecisionContractValidator();
        ReviewToolDecision decision = new ReviewToolDecision(
                "draft_revision",
                Map.of("sourceTerms", List.of("Le Conde")),
                "revise"
        );

        assertEquals(
                "unexpected_argument:sourceTerms",
                validator.validate(decision, ReviewToolRegistry.defaultRegistry()).orElseThrow()
        );
    }
}
