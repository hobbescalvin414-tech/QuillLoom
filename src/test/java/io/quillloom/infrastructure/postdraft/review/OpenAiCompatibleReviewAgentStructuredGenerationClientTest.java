package io.quillloom.infrastructure.postdraft.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quillloom.application.postdraft.review.model.EvidenceSufficiency;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermsProposal;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolDefinition;
import io.quillloom.application.postdraft.review.model.ToolArgumentSchema;
import io.quillloom.application.postdraft.review.model.ToolRepeatPolicy;
import io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException;
import io.quillloom.application.postdraft.review.port.out.LlmTransientException;
import io.quillloom.application.postdraft.review.service.ReviewToolDecisionContractValidator;
import io.quillloom.application.postdraft.review.service.ReviewToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiCompatibleReviewAgentStructuredGenerationClientTest {

    @Test
    void shouldRequestJsonSchemaAndParseEvaluationDecision() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "recommendedStrategy": "DEEP_EDIT",
                          "strategyReason": "need deeper revision",
                          "evidenceSufficiency": "SUFFICIENT",
                          "continueInvestigation": false
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        ReviewAgentEvaluation evaluation = client.generateEvaluationDecision(null, "prompt");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        ChatRequest request = captor.getValue();

        assertEquals(ReviewStrategy.DEEP_EDIT, evaluation.recommendedStrategy());
        assertEquals(EvidenceSufficiency.SUFFICIENT, evaluation.evidenceSufficiency());
        assertNotNull(request);
        assertEquals(ResponseFormatType.JSON, request.responseFormat().type());
        assertNotNull(request.responseFormat().jsonSchema());
    }

    @Test
    void shouldParseNextToolDecision() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "complete_working_set",
                          "arguments": {
                            "chunkIds": ["chunk-1", "chunk-2"]
                          },
                          "reason": "enough evidence"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        ReviewToolDecision decision = client.generateNextToolDecision(null, "prompt");

        assertEquals("complete_working_set", decision.toolName());
        assertTrue(decision.arguments().containsKey("chunkIds"));
    }

    @Test
    void shouldParseRecordConfirmedTermsProposal() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "action": "RECORD_CONFIRMED_TERMS",
                          "reason": "stable pair found",
                          "entries": [
                            {"sourceTerm": "Le Bouquet", "targetTerm": "Bouquet Cafe"}
                          ]
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        RecordConfirmedTermsProposal proposal = client.generateRecordConfirmedTermsProposal("system", "user");

        assertEquals(RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS, proposal.action());
        assertEquals(1, proposal.entries().size());
        assertEquals("Le Bouquet", proposal.entries().get(0).sourceTerm());
        assertEquals("Bouquet Cafe", proposal.entries().get(0).targetTerm());
    }

    @Test
    void shouldRequestRecordConfirmedTermsProposalSchema() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "action": "NOT_APPLICABLE",
                          "reason": "not enough evidence",
                          "entries": []
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        client.generateRecordConfirmedTermsProposal("system", "user");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String schemaText = String.valueOf(captor.getValue().responseFormat().jsonSchema());

        assertTrue(schemaText.contains("sourceTerm"));
        assertTrue(schemaText.contains("targetTerm"));
        assertTrue(schemaText.contains("action"));
    }

    @Test
    void shouldRejectRecordConfirmedTermsProposalWhenApplicableButEntriesEmpty() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "action": "RECORD_CONFIRMED_TERMS",
                          "reason": "stable pair found",
                          "entries": []
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        LlmStructuredOutputException error = assertThrows(
                LlmStructuredOutputException.class,
                () -> client.generateRecordConfirmedTermsProposal("system", "user")
        );
        assertTrue(error.getMessage().contains("parsed"));
        assertTrue(error.getMessage().contains("rawOutput="));
    }

    @Test
    void shouldRejectRecordConfirmedTermsProposalWhenActionIsUnknown() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "action": "UNKNOWN_ACTION",
                          "reason": "bad action",
                          "entries": []
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        LlmStructuredOutputException error = assertThrows(
                LlmStructuredOutputException.class,
                () -> client.generateRecordConfirmedTermsProposal("system", "user")
        );
        assertTrue(error.getMessage().contains("parsed"));
    }

    @Test
    void shouldThrowTransientExceptionWhenStructuredGenerationOutputIsBlank() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("   "))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        LlmTransientException error = assertThrows(
                LlmTransientException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );

        assertTrue(error.getMessage().contains("blank"));
    }

    @Test
    void shouldMapRateLimitExceptionToTransientFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RateLimitException("rate limited"));

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        assertThrows(
                LlmTransientException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );
    }

    @Test
    void shouldMapHttp503ToTransientFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new HttpException(503, "service unavailable"));

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        assertThrows(
                LlmTransientException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );
    }

    @Test
    void shouldMapTimeoutAndConnectFailureToTransientFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new TimeoutException("timed out"));

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        assertThrows(
                LlmTransientException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );
    }

    @Test
    void shouldMapConnectFailureToTransientFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException(new ConnectException("connection refused")));

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        assertThrows(
                LlmTransientException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );
    }

    @Test
    void shouldMapGoAwayAndHttp2IoFailureToTransientFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(
                new RuntimeException(new IOException("GOAWAY received; HTTP/2 connection closed"))
        );

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        assertThrows(
                LlmTransientException.class,
                () -> client.generateEvaluationDecision(null, "prompt")
        );
    }

    @Test
    void shouldNotMapAuthenticationFailureToTransientFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new AuthenticationException("bad key"));

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        assertThrows(
                AuthenticationException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );
    }

    @Test
    void shouldThrowStructuredOutputExceptionWhenJsonCannotBeParsed() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("{not-json"))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        LlmStructuredOutputException error = assertThrows(
                LlmStructuredOutputException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );

        assertTrue(error.getMessage().contains("parsed"));
        assertTrue(error.getMessage().contains("rawOutput={not-json"));
    }

    @Test
    void shouldRequestInvestigationSchemaThatExplicitlyDeclaresChunkIdsAndCount() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "evaluate_focus",
                          "arguments": {},
                          "reason": "need evaluation"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        client.generateNextToolDecision(null, "prompt");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String schemaText = String.valueOf(captor.getValue().responseFormat().jsonSchema());

        assertTrue(schemaText.contains("chunkIds"));
        assertTrue(schemaText.contains("count"));
        assertFalse(schemaText.contains("finalTranslations"));
        assertFalse(schemaText.contains("additionalProperties=true"));
    }

    @Test
    void shouldExposeToolDefinitionContractsInInvestigationSchemaText() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "evaluate_focus",
                          "arguments": {},
                          "reason": "need evaluation"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        client.generateNextToolDecision(null, "prompt");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String schemaText = String.valueOf(captor.getValue().responseFormat().jsonSchema());

        assertTrue(schemaText.contains("read_confirmed_terms"));
        assertTrue(schemaText.contains("sourceTerms"));
        assertTrue(schemaText.contains("argumentRequirements=sourceTerms: string[] (required)"));
        assertTrue(schemaText.contains("request_human_review"));
        assertTrue(schemaText.contains("arguments must be {}"));
        assertTrue(schemaText.contains("Only arguments declared by the selected tool definition are allowed"));
        assertFalse(schemaText.contains("whenToUse="));
        assertFalse(schemaText.contains("resultSemantics="));
    }

    @Test
    void shouldExposeExactWhitelistInInvestigationSchemaText() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "evaluate_focus",
                          "arguments": {},
                          "reason": "need evaluation"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        client.generateNextToolDecision(null, "prompt");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String schemaText = String.valueOf(captor.getValue().responseFormat().jsonSchema());

        assertTrue(schemaText.contains("Allowed toolNames are exactly"));
        assertTrue(schemaText.contains("read_previous_chunks"));
        assertTrue(schemaText.contains("complete_project"));
        assertTrue(schemaText.contains("Do not invent aliases"));
        assertTrue(schemaText.contains("read_adjacent_chunks"));
    }

    @Test
    void shouldExposeRecordConfirmedTermsEntriesSchemaDescriptionFromRegistryContract() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "evaluate_focus",
                          "arguments": {},
                          "reason": "need evaluation"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        client.generateNextToolDecision(null, "prompt");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String schemaText = String.valueOf(captor.getValue().responseFormat().jsonSchema());
        String registryEntriesSchemaDescription = ReviewToolRegistry.defaultRegistry()
                .require("record_confirmed_terms")
                .findArgumentSchema("entries")
                .orElseThrow()
                .schemaDescription();

        assertTrue(schemaText.contains("record_confirmed_terms"));
        assertTrue(schemaText.contains("<source-term>"));
        assertTrue(schemaText.contains("<target-term>"));
        assertTrue(schemaText.contains("Non-empty JSON map from source term to target term."));
        assertTrue(schemaText.contains("later proposal stage"));
        assertFalse(schemaText.contains("candidate pairs must appear in arguments.entries, not only in reason."));
        assertTrue(registryEntriesSchemaDescription.contains("candidate pairs must appear in arguments.entries, not only in reason."));
        assertTrue(schemaText.contains("\"sourceTerm\""));
        assertTrue(schemaText.contains("[\"A=B\"]"));
        assertFalse(schemaText.contains("whenToUse="));
        assertFalse(schemaText.contains("whenNotToUse="));
        assertFalse(schemaText.contains("repeatPolicy="));
    }

    @Test
    void shouldStateThatRecordConfirmedTermsUsesRouteStageSemanticsInNextStepSchema() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "evaluate_focus",
                          "arguments": {},
                          "reason": "need evaluation"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        client.generateNextToolDecision(null, "prompt");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String schemaText = String.valueOf(captor.getValue().responseFormat().jsonSchema());

        assertTrue(schemaText.contains("record_confirmed_terms"));
        assertTrue(schemaText.contains("only selects that tool path"));
        assertTrue(schemaText.contains("later proposal stage"));
        assertFalse(schemaText.contains("candidate pairs must be written in arguments.entries"));
    }

    @Test
    void shouldDeriveEntriesSchemaDescriptionFromInjectedRegistryInsteadOfHardcodedPhrase() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "record_confirmed_terms",
                          "arguments": {
                            "entries": {"Aster":"Aster translated"}
                          },
                          "reason": "record term"
                        }
                        """))
                .build());

        ReviewToolRegistry registry = new ReviewToolRegistry(List.of(
                ReviewToolDefinition.builder("record_confirmed_terms", "record confirmed terms")
                        .whenToUse("use")
                        .whenNotToUse("avoid")
                        .resultSemantics("result")
                        .repeatPolicy(ToolRepeatPolicy.STATE_TRANSITION_ONLY)
                        .nextStepGuidance("next")
                        .requiredArguments(Set.of("entries"))
                        .argumentSchemas(List.of(
                                new ToolArgumentSchema(
                                        "entries",
                                        "object{string:string}",
                                        true,
                                        "custom string map only; use {\"Aster\":\"Aster translated\"}; reject pair objects"
                                )
                        ))
                        .build()
        ));

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(
                        chatModel,
                        new ObjectMapper(),
                        registry,
                        new ReviewToolDecisionContractValidator()
                );

        client.generateNextToolDecision(null, "prompt");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String schemaText = String.valueOf(captor.getValue().responseFormat().jsonSchema());

        assertTrue(schemaText.contains("Aster"));
        assertTrue(schemaText.contains("Aster translated"));
        assertTrue(schemaText.contains("custom string map only"));
        assertFalse(schemaText.contains("do not use sourceTerm/targetTerm objects or arrays"));
    }

    @Test
    void shouldRejectToolDecisionWhenExplanationIsNestedInArguments() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "read_confirmed_terms",
                          "arguments": {
                            "sourceTerms": ["Le Conde"],
                            "reason": "need confirmed terms"
                          },
                          "reason": "need confirmed terms"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        LlmStructuredOutputException error = assertThrows(
                LlmStructuredOutputException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );

        assertTrue(error.getMessage().contains("unexpected_argument:reason"));
    }

    @Test
    void shouldAttachStructuredReviewAgentErrorContextWhenToolNameIsUnregistered() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "read_adjacent_chunks",
                          "arguments": {
                            "count": 1
                          },
                          "reason": "need adjacent context"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        LlmStructuredOutputException error = assertThrows(
                LlmStructuredOutputException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );

        LlmStructuredOutputException.ReviewAgentErrorContext context = error.reviewAgentErrorContext();
        assertNotNull(context);
        assertEquals("unregistered_tool", context.validationError());
        assertEquals("read_adjacent_chunks", context.previousInvalidToolName());
    }

    @Test
    void shouldRejectCompleteWorkingSetDecisionWhenChunkIdsAreMissingInStructuredResult() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "complete_working_set",
                          "arguments": {},
                          "reason": "complete_working_set requires chunkIds, for example chunkIds=['chunk-1']"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        LlmStructuredOutputException error = assertThrows(
                LlmStructuredOutputException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );

        assertTrue(error.getMessage().contains("invalid structured tool decision"));
        assertTrue(error.getMessage().contains("missing_argument:chunkIds"));
    }

    @Test
    void shouldRejectStructuredToolDecisionWhenArgumentsContainNullValue() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "read_next_chunks",
                          "arguments": {
                            "count": null
                          },
                          "reason": "need context"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        LlmStructuredOutputException error = assertThrows(
                LlmStructuredOutputException.class,
                () -> client.generateNextToolDecision(null, "prompt")
        );

        assertTrue(error.getMessage().contains("invalid structured tool decision"));
        assertTrue(error.getMessage().contains("missing_argument:count"));
    }

    @Test
    void shouldStripUnionSchemaArgumentsThatDoNotBelongToSelectedTool() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "read_confirmed_terms",
                          "arguments": {
                            "sourceTerms": ["Le Conde"],
                            "chunkIds": ["chunk-4"],
                            "queryTerms": ["cafe"]
                          },
                          "reason": "need confirmed terms"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        ReviewToolDecision decision = client.generateNextToolDecision(null, "prompt");

        assertEquals("read_confirmed_terms", decision.toolName());
        assertTrue(decision.arguments().containsKey("sourceTerms"));
        assertFalse(decision.arguments().containsKey("chunkIds"));
        assertFalse(decision.arguments().containsKey("queryTerms"));
    }

    @Test
    void shouldIgnoreNullOptionalEntriesWhenCurrentToolDoesNotRequireThem() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "read_confirmed_terms",
                          "arguments": {
                            "sourceTerms": ["Louki"],
                            "entries": null
                          },
                          "reason": "need confirmed terms"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        ReviewToolDecision decision = client.generateNextToolDecision(null, "prompt");

        assertEquals("read_confirmed_terms", decision.toolName());
        assertEquals(List.of("Louki"), decision.arguments().get("sourceTerms"));
        assertFalse(decision.arguments().containsKey("entries"));
    }

    @Test
    void shouldAllowRouteStageRecordConfirmedTermsWithoutEntries() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "record_confirmed_terms",
                          "arguments": {},
                          "reason": "record term"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        ReviewToolDecision decision = client.generateNextToolDecision(null, "prompt");

        assertEquals("record_confirmed_terms", decision.toolName());
        assertTrue(decision.arguments().isEmpty());
    }

    @Test
    void shouldAllowRouteStageRecordConfirmedTermsPairObjectWithoutRejectingEntries() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "record_confirmed_terms",
                          "arguments": {
                            "entries": {
                              "sourceTerm": "Bernolle",
                              "targetTerm": "Bernolle translated"
                            }
                          },
                          "reason": "record term"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        ReviewToolDecision decision = client.generateNextToolDecision(null, "prompt");

        assertEquals("record_confirmed_terms", decision.toolName());
        assertTrue(decision.arguments().containsKey("entries"));
    }

    @Test
    void shouldAllowRouteStageRecordConfirmedTermsWithEmptyEntriesObject() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "toolName": "record_confirmed_terms",
                          "arguments": {
                            "entries": {}
                          },
                          "reason": "record term"
                        }
                        """))
                .build());

        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

        ReviewToolDecision decision = client.generateNextToolDecision(null, "prompt");

        assertEquals("record_confirmed_terms", decision.toolName());
        assertTrue(decision.arguments().containsKey("entries"));
    }
}

