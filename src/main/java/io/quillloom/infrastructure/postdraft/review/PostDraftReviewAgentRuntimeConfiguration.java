package io.quillloom.infrastructure.postdraft.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quillloom.application.postdraft.assembler.PostDraftReviewPackageAssembler;
import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.postdraft.review.port.out.HumanInTheLoopGateway;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewBaselineStore;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentTermWriter;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentWriter;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.port.out.ReviewSessionStore;
import io.quillloom.application.postdraft.review.service.DefaultProjectReviewRuntimePersistenceHook;
import io.quillloom.application.postdraft.review.service.ConsoleReviewRuntimeVisualizer;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentService;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProblemClassifier;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.application.postdraft.review.service.ProjectReviewRuntimePersistenceHook;
import io.quillloom.application.postdraft.review.service.ReviewAgentPromptDumpWriter;
import io.quillloom.application.postdraft.review.service.ReviewRuntimeVisualizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({ReviewAgentLlmProperties.class, ReviewAgentRuntimeProperties.class})
public class PostDraftReviewAgentRuntimeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PostDraftReviewAgentRuntimeConfiguration.class);

    @Bean
    public ReviewAgentStructuredGenerationPort reviewAgentStructuredGenerationPort(ReviewAgentLlmProperties properties,
                                                                                   ObjectMapper objectMapper) {
        validate(properties);

        log.info(
                "review_agent_llm_config enabled={} base_host={} model={} timeout_seconds={} client_max_retries={} wrapper_max_attempts={} retry_backoff_ms={} retry_max_backoff_ms={}",
                properties.isEnabled(),
                safeHost(properties.getBaseUrl()),
                properties.getModelName(),
                Math.max(1, properties.getTimeoutSeconds()),
                0,
                properties.getMaxRetries(),
                properties.getRetryBackoff().toMillis(),
                properties.getRetryMaxBackoff().toMillis()
        );

        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .strictJsonSchema(true)
                .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .maxRetries(0)
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogResponses())
                .build();
        ReviewAgentStructuredGenerationPort delegate =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, objectMapper);
        return new RetryingReviewAgentStructuredGenerationPort(
                delegate,
                new ReviewAgentLlmRetryPolicy(
                        properties.getMaxRetries(),
                        properties.getRetryBackoff(),
                        properties.getRetryMaxBackoff(),
                        properties.getRetryBackoffMultiplier(),
                        properties.getRetryJitterFactor()
                )
        );
    }

    @Bean
    public PostDraftReviewSessionFactory postDraftReviewSessionFactory() {
        return new PostDraftReviewSessionFactory();
    }

    @Bean
    public PostDraftReviewProblemClassifier postDraftReviewProblemClassifier() {
        return new PostDraftReviewProblemClassifier();
    }

    @Bean
    public PostDraftReviewProcessSummaryAssembler postDraftReviewProcessSummaryAssembler() {
        return new PostDraftReviewProcessSummaryAssembler();
    }

    @Bean
    public PostDraftReviewAgentWriter postDraftReviewAgentWriter(PostDraftReviewPackageRepository reviewPackageRepository) {
        return new PostgresPostDraftReviewAgentWriter(reviewPackageRepository);
    }

    @Bean
    public ReviewSessionStore reviewSessionStore(ReviewAgentRuntimeProperties properties,
                                                 ObjectMapper objectMapper) {
        return new FileReviewSessionStore(properties.getSessionDirectory(), objectMapper);
    }

    @Bean
    public PostDraftReviewBaselineStore postDraftReviewBaselineStore(ReviewAgentRuntimeProperties properties,
                                                                     PostDraftReviewPackageRepository reviewPackageRepository,
                                                                     ObjectMapper objectMapper) {
        return new FilePostDraftReviewBaselineStore(
                properties.getBaselineDirectory(),
                reviewPackageRepository,
                objectMapper
        );
    }

    @Bean
    public HumanInTheLoopGateway humanInTheLoopGateway() {
        return new InMemoryHumanInTheLoopGateway();
    }

    @Bean
    public ReviewRuntimeVisualizer reviewRuntimeVisualizer(ReviewAgentRuntimeProperties properties) {
        return new ConsoleReviewRuntimeVisualizer(
                System.out,
                properties.getConsolePreviewMaxLength(),
                properties.getConsoleMode()
        );
    }

    @Bean
    public ReviewAgentPromptDumpWriter reviewAgentPromptDumpWriter(ReviewAgentRuntimeProperties properties) {
        if (!properties.isPromptDumpEnabled()) {
            return ReviewAgentPromptDumpWriter.disabled();
        }
        return ReviewAgentPromptDumpWriter.fileBacked(properties.getPromptDumpDirectory());
    }

    @Bean
    public ProjectReviewRuntimePersistenceHook projectReviewRuntimePersistenceHook(PostDraftReviewAgentWriter writer,
                                                                                   ReviewSessionStore reviewSessionStore) {
        return new DefaultProjectReviewRuntimePersistenceHook(writer, reviewSessionStore);
    }

    @Bean
    public PostDraftReviewAgentService postDraftReviewAgentService(PostDraftReviewAgentReader reader,
                                                                   PostDraftReviewSessionFactory sessionFactory,
                                                                   PostDraftReviewProblemClassifier problemClassifier,
                                                                   PostDraftReviewProcessSummaryAssembler summaryAssembler,
                                                                   HumanInTheLoopGateway humanGateway,
                                                                   PostDraftReviewAgentWriter writer,
                                                                   PostDraftReviewAgentTermWriter termWriter,
                                                                   ReviewAgentStructuredGenerationPort generationPort,
                                                                   ReviewSessionStore reviewSessionStore,
                                                                   PostDraftReviewBaselineStore baselineStore,
                                                                   ReviewRuntimeVisualizer runtimeVisualizer,
                                                                   ProjectReviewRuntimePersistenceHook persistenceHook,
                                                                   ReviewAgentPromptDumpWriter promptDumpWriter,
                                                                   ReviewAgentRuntimeProperties runtimeProperties) {
        return new PostDraftReviewAgentService(
                reader,
                sessionFactory,
                problemClassifier,
                summaryAssembler,
                humanGateway,
                writer,
                termWriter,
                generationPort,
                reviewSessionStore,
                runtimeVisualizer,
                persistenceHook,
                baselineStore,
                promptDumpWriter,
                runtimeProperties.getMaxWallClockMinutes()
        );
    }

    private void validate(ReviewAgentLlmProperties properties) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("启用 postdraft review llm runtime 时必须显式设置 quillloom.postdraft.review.llm.enabled=true");
        }
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("启用 postdraft review llm runtime 时必须提供 baseUrl");
        }
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("启用 postdraft review llm runtime 时必须提供 apiKey");
        }
        if (isBlank(properties.getModelName())) {
            throw new IllegalStateException("启用 postdraft review llm runtime 时必须提供 modelName");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeHost(String baseUrl) {
        if (isBlank(baseUrl)) {
            return "(blank)";
        }
        try {
            URI uri = URI.create(baseUrl);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (IllegalArgumentException ex) {
            return "(invalid-url)";
        }
    }
}
