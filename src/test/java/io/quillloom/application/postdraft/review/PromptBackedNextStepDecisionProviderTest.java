package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.FocusReviewDiagnostics;
import io.quillloom.application.postdraft.review.model.HistoryLog;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectIssueBacklog;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermEntry;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermsProposal;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewVisitedObjects;
import io.quillloom.application.postdraft.review.model.ReviewWorkingSet;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.model.TranscriptStore;
import io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException;
import io.quillloom.application.postdraft.review.port.out.LlmTransientException;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.prompt.InvestigationPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.ReviewAgentSystemPromptBuilder;
import io.quillloom.application.postdraft.review.service.RecordConfirmedTermsAssemblyException;
import io.quillloom.application.postdraft.review.service.RecordConfirmedTermsProposalException;
import io.quillloom.application.postdraft.review.service.PromptBackedNextStepDecisionProvider;
import io.quillloom.application.postdraft.review.service.ReviewAgentNextStepStructuredOutputException;
import io.quillloom.application.postdraft.review.service.ReviewAgentPromptDumpWriter;
import io.quillloom.application.postdraft.review.service.ReviewToolDecisionContractValidator;
import io.quillloom.application.postdraft.review.service.ReviewToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBackedNextStepDecisionProviderTest {

    private static final List<String> MOJIBAKE_MARKERS = List.of(
            "\\u95c2",
            "\\u6fde",
            "\\u95c1",
            "\\u95b3",
            "\\u95bf",
            "\\u95b8",
            "\\u9420",
            "\\u6924",
            "\\u7f02",
            "\\u7ec1"
    );

    @Test
    void shouldRetryWhenCompleteWorkingSetOmitsChunkIds() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("complete_working_set", Map.of(), "done"),
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1", "chunk-2")), "fixed")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSession());

        assertEquals("complete_working_set", decision.toolName());
        assertEquals(List.of("chunk-1", "chunk-2"), decision.arguments().get("chunkIds"));
        assertEquals(2, generationPort.prompts().size());
        assertTrue(generationPort.prompts().get(1).contains("validationError: missing_argument:chunkIds"));
        assertTrue(generationPort.prompts().get(1).contains("anchorChunkId: chunk-1"));
        assertTrue(generationPort.prompts().get(1).contains("currentWorkingSet: [chunk-1, chunk-2]"));
    }

    @Test
    void shouldRetryWhenReadPreviousChunksOmitsCount() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("read_previous_chunks", Map.of(), "need context"),
                new ReviewToolDecision("read_previous_chunks", Map.of("count", 1), "fixed")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSession());

        assertEquals("read_previous_chunks", decision.toolName());
        assertEquals(1, decision.arguments().get("count"));
        assertEquals(2, generationPort.prompts().size());
        assertTrue(generationPort.prompts().get(1).contains("validationError: missing_argument:count"));
    }

    @Test
    void shouldAllowMultipleRepairAttemptsBeforeAcceptingValidDecision() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("read_previous_chunks", Map.of(), "missing count #1"),
                new ReviewToolDecision("read_previous_chunks", Map.of(), "missing count #2"),
                new ReviewToolDecision("read_previous_chunks", Map.of(), "missing count #3"),
                new ReviewToolDecision("read_previous_chunks", Map.of("count", 1), "fixed")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSession());

        assertEquals("read_previous_chunks", decision.toolName());
        assertEquals(1, decision.arguments().get("count"));
        assertEquals(4, generationPort.prompts().size());
    }

    @Test
    void shouldThrowStableNextStepExceptionWhenRepairAttemptsReachUpperBound() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("read_previous_chunks", Map.of(), "missing count #1"),
                new ReviewToolDecision("read_previous_chunks", Map.of(), "missing count #2"),
                new ReviewToolDecision("read_previous_chunks", Map.of(), "missing count #3"),
                new ReviewToolDecision("read_previous_chunks", Map.of(), "missing count #4"),
                new ReviewToolDecision("read_previous_chunks", Map.of(), "missing count #5"),
                new ReviewToolDecision("read_previous_chunks", Map.of(), "missing count #6")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewAgentNextStepStructuredOutputException error = assertThrows(
                ReviewAgentNextStepStructuredOutputException.class,
                () -> provider.decide(sampleSession())
        );

        assertTrue(error.getMessage().contains("missing_argument:count"));
        assertEquals(6, generationPort.prompts().size());
    }

    @Test
    void shouldRetryWhenStructuredOutputIsInvalidBeforeDecisionCanBeParsed() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new LlmStructuredOutputException("Review agent invalid structured tool decision: null_argument:chunkIds"),
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "fixed")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSession());

        assertEquals("complete_working_set", decision.toolName());
        assertEquals(List.of("chunk-1"), decision.arguments().get("chunkIds"));
        assertEquals(2, generationPort.prompts().size());
        assertTrue(generationPort.prompts().get(1).contains("structuredOutputError: Review agent invalid structured tool decision: null_argument:chunkIds"));
        assertTrue(generationPort.prompts().get(1).contains("arguments"));
        assertFalse(generationPort.prompts().get(1).contains("[entries repair]"));
        assertNoMojibake(generationPort.prompts().get(1));
    }

    @Test
    void shouldRetryStructuredOutputEntriesFailureWithExecutableEntriesRepairGuidance() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new LlmStructuredOutputException(
                        "Review agent invalid structured tool decision: invalid_argument:entries; rawOutput={\"toolName\":\"record_confirmed_terms\",\"arguments\":{\"entries\":{}},\"reason\":\"record term\"}"
                ),
                new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Bernolle", "Bernolle-fixed")), "fixed")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSession());

        assertEquals("record_confirmed_terms", decision.toolName());
        assertEquals(Map.of("Bernolle", "Bernolle-fixed"), decision.arguments().get("entries"));
        assertEquals(2, generationPort.prompts().size());
        String repairPrompt = generationPort.prompts().get(1);
        assertTrue(repairPrompt.contains("invalid_argument:entries"));
        assertTrue(repairPrompt.contains("arguments.entries"));
        assertTrue(repairPrompt.contains("Option A"));
        assertTrue(repairPrompt.contains("Option B"));
        assertTrue(repairPrompt.contains("\"entries\": {}"));
        assertTrue(repairPrompt.contains("{\"entries\":{\"sourceTerm\":\"...\",\"targetTerm\":\"...\"}}"));
        assertTrue(repairPrompt.contains("{\"entries\":[{\"sourceTerm\":\"...\",\"targetTerm\":\"...\"}]}"));
        assertTrue(repairPrompt.contains("{\"entries\":[\"A=B\"]}"));
        assertTrue(repairPrompt.contains("{\"entries\":{\"<source-term>\":\"<target-term>\"}}"));
        assertTrue(repairPrompt.contains("Forbidden third output"));
        assertTrue(repairPrompt.contains("tool/arguments are still invalid"));
        assertTrue(repairPrompt.contains("union/schema/argument-conflict analysis in reason"));
        assertTrue(repairPrompt.contains("extra explanatory text outside JSON"));
        assertNoMojibake(repairPrompt);
    }

    @Test
    void shouldNotEnterProposalPathBeforeStandardDecisionChoosesRecordConfirmedTerms() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Bouquet")), "check project-level naming first")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
                List.of("anchorChunk={sourceText=Le Bouquet, translatedText=????????????, confirmedTermUpdates={Le Bouquet=????????}"),
                List.of(),
                List.of()
        ));

        assertEquals("read_confirmed_terms", decision.toolName());
        assertTrue(generationPort.proposalPrompts().isEmpty());
        assertEquals(1, generationPort.prompts().size());
    }

    @Test
    void shouldNotEnterProposalPathWhenOnlyLowPrioritySignalsExist() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("evaluate_focus", Map.of(), "need more evidence")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
                List.of(),
                List.of("decision=note about term", "translatorCommentary=maybe use Bouquet Cafe", "confirmedTermLookupMiss=[Le Bouquet]"),
                List.of()
        ));

        assertEquals("evaluate_focus", decision.toolName());
        assertTrue(generationPort.proposalPrompts().isEmpty());
        assertEquals(1, generationPort.prompts().size());
    }


    @Test
    void shouldEnterProposalPathOnlyAfterStandardDecisionChoosesRecordConfirmedTerms() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
                new RecordConfirmedTermsProposal(
                        RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                        "stable pair",
                        List.of(new RecordConfirmedTermEntry("Patrick Modiano", "PatricZh"))
                )
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
                List.of("confirmedTerm=Patrick Modiano->PatricZh"),
                List.of(),
                List.of()
        ));

        assertEquals("record_confirmed_terms", decision.toolName());
        assertEquals(Map.of("Patrick Modiano", "PatricZh"), decision.arguments().get("entries"));
        assertEquals(1, generationPort.proposalPrompts().size());
        assertEquals(1, generationPort.prompts().size());
    }

    @Test
    void shouldReplanNextStepWhenProposalReturnsNotApplicable() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
                new RecordConfirmedTermsProposal(
                        RecordConfirmedTermsProposal.Action.NOT_APPLICABLE,
                        "pair not stable enough",
                        List.of()
                ),
                new ReviewToolDecision("evaluate_focus", Map.of(), "proposal denied term recording; continue with ordinary focus evaluation")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
                List.of("confirmedTerm=Patrick Modiano->PatricZh"),
                List.of(),
                List.of()
        ));

        assertEquals("evaluate_focus", decision.toolName());
        assertEquals(2, generationPort.prompts().size());
        assertEquals(1, generationPort.proposalPrompts().size());
        assertTrue(generationPort.prompts().get(1).contains("proposal_not_applicable"));
    }

    @Test
    void shouldKeepProposalEntryOrderWhenMultiplePairsAreReturned() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed terms"),
                new RecordConfirmedTermsProposal(
                        RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                        "multiple stable pairs",
                        List.of(
                                new RecordConfirmedTermEntry("Patrick Modiano", "PatricZh"),
                                new RecordConfirmedTermEntry("Rue de Rome", "RomeStreetZh")
                        )
                )
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
                List.of("confirmedTerm=Patrick Modiano->PatricZh", "confirmedTerm=Rue de Rome->RomeStreetZh"),
                List.of(),
                List.of()
        ));

        @SuppressWarnings("unchecked")
        Map<String, String> entries = (Map<String, String>) decision.arguments().get("entries");
        assertEquals(List.of("Patrick Modiano", "Rue de Rome"), List.copyOf(entries.keySet()));
    }

    @Test
    void shouldRetryProposalAssemblyFailureWithinUnifiedRepairLoop() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
                new RecordConfirmedTermsProposal(
                        RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                        "conflicting pair",
                        List.of(
                                new RecordConfirmedTermEntry("Patrick Modiano", "PatricZh"),
                                new RecordConfirmedTermEntry("patrick modiano", "OtherZh")
                        )
                ),
                new RecordConfirmedTermsProposal(
                        RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                        "stable pair",
                        List.of(new RecordConfirmedTermEntry("Patrick Modiano", "PatricZh"))
                )
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
                List.of("confirmedTerm=Patrick Modiano->PatricZh"),
                List.of(),
                List.of()
        ));

        assertEquals("record_confirmed_terms", decision.toolName());
        assertEquals(2, generationPort.proposalPrompts().size());
        assertTrue(generationPort.proposalPrompts().get(1).contains("proposal_assembly_error"));
    }

    @Test
    void shouldRetryProposalStructuredFailureWithinUnifiedRepairLoop() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
                new LlmStructuredOutputException("proposal rawOutput=not-json"),
                new RecordConfirmedTermsProposal(
                        RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                        "stable pair",
                        List.of(new RecordConfirmedTermEntry("Patrick Modiano", "PatricZh"))
                )
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
                List.of("confirmedTerm=Patrick Modiano->PatricZh"),
                List.of(),
                List.of()
        ));

        assertEquals("record_confirmed_terms", decision.toolName());
        assertEquals(2, generationPort.proposalPrompts().size());
        assertTrue(generationPort.proposalPrompts().get(1).contains("proposal rawOutput=not-json"));
    }

    @Test
    void shouldApplySingleSixAttemptBudgetAcrossNextStepAndProposal() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new LlmStructuredOutputException("structured generation output cannot be parsed as structured JSON; rawOutput=bad-next-step-1"),
                new LlmStructuredOutputException("structured generation output cannot be parsed as structured JSON; rawOutput=bad-next-step-2"),
                new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
                new LlmStructuredOutputException("proposal rawOutput=bad-proposal-1"),
                new LlmStructuredOutputException("proposal rawOutput=bad-proposal-2"),
                new RecordConfirmedTermsProposal(
                        RecordConfirmedTermsProposal.Action.NOT_APPLICABLE,
                        "still not stable",
                        List.of()
                ),
                new ReviewToolDecision("evaluate_focus", Map.of(), "continue investigation")
        );

        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
                List.of("confirmedTerm=Patrick Modiano->PatricZh"),
                List.of(),
                List.of()
        ));

        assertEquals("evaluate_focus", decision.toolName());
        assertEquals(4, generationPort.prompts().size());
        assertEquals(3, generationPort.proposalPrompts().size());
    }


    @Test
    void shouldNotUseLegacyEntriesRepairAsProposalMainPathRepair() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of()), "record term"),
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Bernolle")), "continue investigation")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSession());

        assertEquals("read_confirmed_terms", decision.toolName());
        assertEquals(2, generationPort.prompts().size());
        String repairPrompt = generationPort.prompts().get(1);
        assertTrue(repairPrompt.contains("validationError: invalid_argument:entries"));
        assertFalse(repairPrompt.contains("[Record Confirmed Terms Proposal Repair]"));
        assertNoMojibake(repairPrompt);
    }

    @Test
    void shouldRequireReasonPairsToAlsoAppearInEntriesDuringEntriesRepair() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision(
                        "record_confirmed_terms",
                        Map.of("entries", Map.of()),
                        "confirmed pair: Editeur d'art->art publisher"
                ),
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Editeur d'art")), "continue investigation")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSession());

        assertEquals("read_confirmed_terms", decision.toolName());
        assertEquals(2, generationPort.prompts().size());
        String repairPrompt = generationPort.prompts().get(1);
        assertTrue(repairPrompt.contains("reason"));
        assertTrue(repairPrompt.contains("source->target term pair"));
        assertTrue(repairPrompt.contains("arguments.entries"));
        assertTrue(repairPrompt.contains("reason already contains explicit pair"));
        assertTrue(repairPrompt.contains("entries={}"));
        assertTrue(repairPrompt.contains("must copy the same pair into arguments.entries"));
        assertTrue(repairPrompt.contains("candidate pairs cannot appear only in reason"));
        assertNoMojibake(repairPrompt);
    }

    @Test
    void shouldNotInjectEntriesRepairContractForNonEntriesDecisionRepairError() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("read_previous_chunks", Map.of(), "need context"),
                new ReviewToolDecision("read_previous_chunks", Map.of("count", 1), "fixed")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        ReviewToolDecision decision = provider.decide(sampleSession());

        assertEquals("read_previous_chunks", decision.toolName());
        assertEquals(2, generationPort.prompts().size());
        String repairPrompt = generationPort.prompts().get(1);
        assertTrue(repairPrompt.contains("validationError: missing_argument:count"));
        assertFalse(repairPrompt.contains("[entries repair]"));
        assertFalse(repairPrompt.contains("Option A"));
        assertFalse(repairPrompt.contains("Option B"));
        assertFalse(repairPrompt.contains("{\"entries\":{\"<source-term>\":\"<target-term>\"}}"));
        assertNoMojibake(repairPrompt);
    }

    @Test
    void shouldNotRepairTransientLlmFailure() {
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new LlmTransientException("rate limited")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator()
        );

        LlmTransientException error = assertThrows(
                LlmTransientException.class,
                () -> provider.decide(sampleSession())
        );

        assertEquals("rate limited", error.getMessage());
        assertEquals(1, generationPort.prompts().size());
    }

    @Test
    void shouldWritePromptDumpFilesForInvestigationRepairAndFinalFailure() throws IOException {
        Path tempDir = createTestOutputDirectory("enabled");
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new LlmStructuredOutputException("structured generation output cannot be parsed as structured JSON; rawOutput=not-json"),
                new LlmStructuredOutputException("structured generation output cannot be parsed as structured JSON; rawOutput=still-not-json"),
                new LlmStructuredOutputException("structured generation output cannot be parsed as structured JSON; rawOutput=third-not-json"),
                new LlmStructuredOutputException("structured generation output cannot be parsed as structured JSON; rawOutput=fourth-not-json"),
                new LlmStructuredOutputException("structured generation output cannot be parsed as structured JSON; rawOutput=fifth-not-json"),
                new LlmStructuredOutputException("structured generation output cannot be parsed as structured JSON; rawOutput=sixth-not-json")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator(),
                ReviewAgentPromptDumpWriter.fileBacked(tempDir)
        );

        try {
            assertThrows(LlmStructuredOutputException.class, () -> provider.decide(sampleSession()));

            List<Path> dumpedFiles = listDumpFiles(tempDir);
            assertEquals(7, dumpedFiles.size());

            Path investigationDump = dumpedFiles.stream()
                    .filter(path -> path.getFileName().toString().contains("-project-1-chunk-1-investigation-attempt-0-PromptCapture.log"))
                    .findFirst()
                    .orElseThrow();
            String investigationDumpText = Files.readString(investigationDump);
            assertTrue(investigationDumpText.contains("projectId=project-1"));
            assertTrue(investigationDumpText.contains("promptKind=investigation"));
            assertTrue(investigationDumpText.contains("exceptionType=PromptCapture"));
            assertTrue(investigationDumpText.contains("[systemPrompt]"));
            assertTrue(investigationDumpText.contains("[userPrompt]"));
            assertNoMojibake(investigationDumpText);

            Path repairDump = dumpedFiles.stream()
                    .filter(path -> path.getFileName().toString().contains("-project-1-chunk-1-structured_output_repair-attempt-1-PromptCapture.log"))
                    .findFirst()
                    .orElseThrow();
            String repairDumpText = Files.readString(repairDump);
            assertTrue(repairDumpText.contains("promptKind=structured_output_repair"));
            assertTrue(repairDumpText.contains("attempt=1"));
            assertTrue(repairDumpText.contains("errorMessage=structured generation output cannot be parsed as structured JSON; rawOutput=not-json"));
            assertTrue(repairDumpText.contains("structuredOutputError=structured generation output cannot be parsed as structured JSON; rawOutput=not-json"));
            assertTrue(repairDumpText.contains("rawOutput=not-json"));
            assertNoMojibake(repairDumpText);

            Path finalDump = dumpedFiles.stream()
                    .filter(path -> path.getFileName().toString().contains("-project-1-chunk-1-structured_output_repair-attempt-5-LlmStructuredOutputException.log"))
                    .findFirst()
                    .orElseThrow();
            String finalDumpText = Files.readString(finalDump);
            assertTrue(finalDumpText.contains("exceptionType=LlmStructuredOutputException"));
            assertTrue(finalDumpText.contains("promptKind=structured_output_repair"));
            assertTrue(finalDumpText.contains("attempt=5"));
            assertTrue(finalDumpText.contains("errorMessage=structured generation output cannot be parsed as structured JSON; rawOutput=sixth-not-json"));
            assertTrue(finalDumpText.contains("rawOutput=sixth-not-json"));
            assertNoMojibake(finalDumpText);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    @Test
    void shouldWriteDecisionRepairPromptDumpWithValidationContext() throws IOException {
        Path tempDir = createTestOutputDirectory("decision-repair");
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new ReviewToolDecision("read_previous_chunks", Map.of(), "need context"),
                new ReviewToolDecision("read_previous_chunks", Map.of("count", 1), "fixed")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator(),
                ReviewAgentPromptDumpWriter.fileBacked(tempDir)
        );

        try {
            ReviewToolDecision decision = provider.decide(sampleSession());

            assertEquals("read_previous_chunks", decision.toolName());
            List<Path> dumpedFiles = listDumpFiles(tempDir);
            assertEquals(2, dumpedFiles.size());

            Path repairDump = dumpedFiles.stream()
                    .filter(path -> path.getFileName().toString().contains("-project-1-chunk-1-decision_repair-attempt-1-PromptCapture.log"))
                    .findFirst()
                    .orElseThrow();
            String repairDumpText = Files.readString(repairDump);
            assertTrue(repairDumpText.contains("promptKind=decision_repair"));
            assertTrue(repairDumpText.contains("toolName=read_previous_chunks"));
            assertTrue(repairDumpText.contains("validationError=missing_argument:count"));
            assertTrue(repairDumpText.contains("errorMessage=need context"));
            assertTrue(repairDumpText.contains("rawOutput=ReviewToolDecision"));
            assertNoMojibake(repairDumpText);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    @Test
    void shouldNotWritePromptDumpFileWhenWriterIsDisabled() throws IOException {
        Path tempDir = createTestOutputDirectory("disabled");
        RecordingGenerationPort generationPort = new RecordingGenerationPort(
                new LlmStructuredOutputException("invalid structured tool decision"),
                new LlmStructuredOutputException("invalid structured tool decision"),
                new LlmStructuredOutputException("invalid structured tool decision"),
                new LlmStructuredOutputException("invalid structured tool decision"),
                new LlmStructuredOutputException("invalid structured tool decision"),
                new LlmStructuredOutputException("invalid structured tool decision")
        );
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                new ReviewAgentSystemPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                generationPort,
                new ReviewToolDecisionContractValidator(),
                ReviewAgentPromptDumpWriter.disabled()
        );

        try {
            assertThrows(LlmStructuredOutputException.class, () -> provider.decide(sampleSession()));

            try (Stream<Path> files = Files.list(tempDir)) {
                assertFalse(files.findAny().isPresent());
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void assertNoMojibake(String text) {
        for (String marker : MOJIBAKE_MARKERS) {
            assertFalse(text.contains(marker), "unexpected mojibake marker: " + marker);
        }
    }

    private static List<Path> listDumpFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.sorted().toList();
        }
    }

    private static Path createTestOutputDirectory(String prefix) throws IOException {
        Path root = Path.of("target", "review-agent-prompt-dumps-test");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, prefix + "-");
    }

    private static void deleteDirectory(Path directory) {
        if (directory == null || Files.notExists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static PostDraftReviewSession sampleSession() {
        return new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                ReviewWorkingSet.fromAnchor("chunk-1").expandTo(List.of("chunk-1", "chunk-2")),
                TranscriptStore.empty(),
                HistoryLog.empty(),
                ReviewEvidenceBundle.empty(),
                ReviewVisitedObjects.empty(),
                List.of(),
                "note",
                Set.of(),
                ReviewStrategy.LIGHT_EDIT,
                FocusReviewDiagnostics.empty()
        );
    }

    private static PostDraftReviewSession sampleSessionWithEvidence(List<String> keyEvidence,
                                                                    List<String> evidence,
                                                                    List<String> transcript) {
        return new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                ReviewWorkingSet.fromAnchor("chunk-1").expandTo(List.of("chunk-1", "chunk-2")),
                new TranscriptStore(transcript, false),
                HistoryLog.empty(),
                new ReviewEvidenceBundle(List.of(), evidence, keyEvidence, List.of(), List.of()),
                ReviewVisitedObjects.empty(),
                List.of(),
                "note",
                Set.of(),
                ReviewStrategy.LIGHT_EDIT,
                FocusReviewDiagnostics.empty(),
                ProjectIssueBacklog.empty(),
                ReviewAgentState.INVESTIGATING,
                false
        );
    }

    private static final class RecordingGenerationPort implements ReviewAgentStructuredGenerationPort {
        private final ArrayDeque<Object> outputs;
        private final java.util.ArrayList<String> prompts = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> proposalPrompts = new java.util.ArrayList<>();

        private RecordingGenerationPort(Object... outputs) {
            this.outputs = new ArrayDeque<>(List.of(outputs));
        }

        @Override
        public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
            prompts.add(userPrompt);
            Object next = outputs.removeFirst();
            if (next instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return (ReviewToolDecision) next;
        }

        @Override
        public RecordConfirmedTermsProposal generateRecordConfirmedTermsProposal(String systemPrompt, String userPrompt) {
            proposalPrompts.add(userPrompt);
            Object next = outputs.removeFirst();
            if (next instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return (RecordConfirmedTermsProposal) next;
        }

        @Override
        public ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
            return new RevisionDraft("revised", RevisionMode.LIGHT_EDIT, List.of(), List.of());
        }

        @Override
        public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
            return new RevisionSelfCheckResult(true, "", List.of());
        }

        private List<String> prompts() {
            return List.copyOf(prompts);
        }

        private List<String> proposalPrompts() {
            return List.copyOf(proposalPrompts);
        }
    }
}

