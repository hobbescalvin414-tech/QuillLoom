package io.quillloom.infrastructure.postdraft.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler;
import io.quillloom.application.postdraft.assembler.PostDraftReviewPackageAssembler;
import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentTermWriter;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentWriter;
import io.quillloom.application.postdraft.review.port.out.ReviewSessionStore;
import io.quillloom.application.postdraft.review.service.ConsoleReviewRuntimeVisualizer;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentService;
import io.quillloom.application.postdraft.review.service.ReviewAgentPromptDumpWriter;
import io.quillloom.application.postdraft.review.service.ReviewRuntimeVisualizer;
import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.application.translation.port.out.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PostDraftReviewAgentRuntimeConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PostDraftReviewAgentRuntimeConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PostDraftReviewPackageRepository.class, NoOpPostDraftReviewPackageRepository::new)
            .withBean(ProjectKnowledgeBaseRepository.class, NoOpProjectKnowledgeBaseRepository::new)
            .withBean(PostDraftContinuationContextAssembler.class, PostDraftContinuationContextAssembler::new)
            .withBean(PostDraftReviewPackageAssembler.class, PostDraftReviewPackageAssembler::new)
            .withBean(KnowledgeRetrievalService.class, NoOpKnowledgeRetrievalService::new)
            .withBean(PostDraftReviewAgentReader.class,
                    () -> new RepositoryBackedPostDraftReviewAgentReader(
                            new NoOpPostDraftReviewPackageRepository(),
                            new NoOpProjectKnowledgeBaseRepository(),
                            new PostDraftContinuationContextAssembler(),
                            new NoOpKnowledgeRetrievalService()
                    ))
            .withBean(PostDraftReviewAgentTermWriter.class,
                    () -> new RepositoryBackedPostDraftReviewAgentTermWriter(
                            new NoOpPostDraftReviewPackageRepository(),
                            new PostDraftReviewPackageAssembler(),
                            new RepositoryBackedPostDraftReviewAgentReader(
                                    new NoOpPostDraftReviewPackageRepository(),
                                    new NoOpProjectKnowledgeBaseRepository(),
                                    new PostDraftContinuationContextAssembler(),
                                    new NoOpKnowledgeRetrievalService()
                            )
                    ))
            .withPropertyValues(
                    "quillloom.postdraft.review.llm.enabled=true",
                    "quillloom.postdraft.review.llm.base-url=http://localhost",
                    "quillloom.postdraft.review.llm.api-key=test-key",
                    "quillloom.postdraft.review.llm.model-name=test-model",
                    "quillloom.postdraft.review.runtime.session-directory=target/test-review-agent-runtime"
            );

    @Test
    void shouldAssembleReviewAgentRuntimeBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PostDraftReviewAgentService.class);
            assertThat(context).hasSingleBean(ReviewSessionStore.class);
            assertThat(context).hasSingleBean(ReviewAgentPromptDumpWriter.class);
            assertThat(context.getBean(PostDraftReviewAgentWriter.class))
                    .isInstanceOf(PostgresPostDraftReviewAgentWriter.class);
            assertThat(context.getBean(ReviewRuntimeVisualizer.class))
                    .isInstanceOf(ConsoleReviewRuntimeVisualizer.class);
        });
    }

    @Test
    void shouldCreateFileBackedPromptDumpWriterWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "quillloom.postdraft.review.runtime.prompt-dump-enabled=true",
                        "quillloom.postdraft.review.runtime.prompt-dump-directory=target/test-review-agent-prompt-dumps"
                )
                .run(context -> assertThat(context.getBean(ReviewAgentPromptDumpWriter.class).getClass().getSimpleName())
                        .isEqualTo("FileBackedPromptDumpWriter"));
    }

    private static final class NoOpPostDraftReviewPackageRepository implements PostDraftReviewPackageRepository {
        @Override
        public Optional<io.quillloom.domain.postdraft.PostDraftReviewPackage> load(String projectId) {
            return Optional.empty();
        }

        @Override
        public void save(io.quillloom.domain.postdraft.PostDraftReviewPackage reviewPackage) {
        }
    }

    private static final class NoOpProjectKnowledgeBaseRepository implements ProjectKnowledgeBaseRepository {
        @Override
        public Optional<io.quillloom.domain.knowledge.ProjectKnowledgeBase> load(String projectId) {
            return Optional.empty();
        }

        @Override
        public void save(io.quillloom.domain.knowledge.ProjectKnowledgeBase knowledgeBase) {
        }
    }

    private static final class NoOpKnowledgeRetrievalService implements KnowledgeRetrievalService {
        @Override
        public io.quillloom.application.translation.model.KnowledgeRetrievalResult retrieve(
                String projectId,
                io.quillloom.domain.knowledge.ProjectKnowledgeBase preferredKnowledgeBase,
                io.quillloom.application.translation.model.KnowledgeRetrievalQuery query) {
            return new io.quillloom.application.translation.model.KnowledgeRetrievalResult(java.util.List.of());
        }
    }
}
