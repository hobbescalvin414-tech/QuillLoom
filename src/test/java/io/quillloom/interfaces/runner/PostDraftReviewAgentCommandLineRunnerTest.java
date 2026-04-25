package io.quillloom.interfaces.runner;

import io.quillloom.application.postdraft.review.command.StartProjectPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewProjectStopReason;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.port.out.HumanInTheLoopGateway;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentWriter;
import io.quillloom.application.postdraft.review.port.out.ReviewSessionStore;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentService;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProblemClassifier;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.infrastructure.postdraft.review.ReviewAgentRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostDraftReviewAgentCommandLineRunnerTest {

    @Test
    void shouldResumeProjectWhenCliActionIsResume() throws Exception {
        ReviewAgentRuntimeProperties properties = new ReviewAgentRuntimeProperties();
        properties.setCliEnabled(true);
        properties.setCliAction("resume");
        properties.setCliProjectId("project-1");
        RecordingService service = new RecordingService();
        PostDraftReviewAgentCommandLineRunner runner = new PostDraftReviewAgentCommandLineRunner(service, properties);

        runner.run("--humanReviewNote=Louki 统一译为露姬");

        assertEquals("project-1", service.resumedProjectId);
        assertEquals("Louki 统一译为露姬", service.resumedHumanReviewNote);
    }

    @Test
    void shouldFallbackToConfiguredHumanReviewNoteWhenResumeArgIsMissing() throws Exception {
        ReviewAgentRuntimeProperties properties = new ReviewAgentRuntimeProperties();
        properties.setCliEnabled(true);
        properties.setCliAction("resume");
        properties.setCliProjectId("project-1");
        properties.setCliHumanReviewNote("配置里的人工说明");
        RecordingService service = new RecordingService();
        PostDraftReviewAgentCommandLineRunner runner = new PostDraftReviewAgentCommandLineRunner(service, properties);

        runner.run();

        assertEquals("project-1", service.resumedProjectId);
        assertEquals("配置里的人工说明", service.resumedHumanReviewNote);
    }

    @Test
    void shouldResetProjectWhenCliActionIsReset() throws Exception {
        ReviewAgentRuntimeProperties properties = new ReviewAgentRuntimeProperties();
        properties.setCliEnabled(true);
        properties.setCliAction("reset");
        properties.setCliProjectId("project-9");
        RecordingService service = new RecordingService();
        PostDraftReviewAgentCommandLineRunner runner = new PostDraftReviewAgentCommandLineRunner(service, properties);

        runner.run();

        assertEquals("project-9", service.resetProjectId);
    }

    @Test
    void shouldCreateBaselineWhenCliActionIsCreateBaseline() throws Exception {
        ReviewAgentRuntimeProperties properties = new ReviewAgentRuntimeProperties();
        properties.setCliEnabled(true);
        properties.setCliAction("create-baseline");
        properties.setCliProjectId("project-7");
        RecordingService service = new RecordingService();
        PostDraftReviewAgentCommandLineRunner runner = new PostDraftReviewAgentCommandLineRunner(service, properties);

        runner.run();

        assertEquals("project-7", service.baselineProjectId);
    }

    @Test
    void shouldResetProjectFromBaselineWhenCliActionIsResetFromBaseline() throws Exception {
        ReviewAgentRuntimeProperties properties = new ReviewAgentRuntimeProperties();
        properties.setCliEnabled(true);
        properties.setCliAction("reset-from-baseline");
        properties.setCliProjectId("project-8");
        RecordingService service = new RecordingService();
        PostDraftReviewAgentCommandLineRunner runner = new PostDraftReviewAgentCommandLineRunner(service, properties);

        runner.run();

        assertEquals("project-8", service.resetFromBaselineProjectId);
    }

    @Test
    void shouldFailStartActionWhenProjectReviewStopsWithBusinessFailure() {
        ReviewAgentRuntimeProperties properties = new ReviewAgentRuntimeProperties();
        properties.setCliEnabled(true);
        properties.setCliAction("start");
        properties.setCliProjectId("project-llm-failed");
        RecordingService service = new RecordingService();
        service.reviewProjectResult = resultWithStopReason(ReviewProjectStopReason.LLM_CALL_FAILED);
        PostDraftReviewAgentCommandLineRunner runner = new PostDraftReviewAgentCommandLineRunner(service, properties);

        IllegalStateException error = assertThrows(IllegalStateException.class, runner::run);

        assertEquals("project-llm-failed", service.reviewedProjectId);
        assertEquals("review agent project run failed: stopReason=llm_call_failed", error.getMessage());
    }

    @Test
    void shouldFailResumeActionWhenProjectReviewStopsWithBusinessFailure() {
        ReviewAgentRuntimeProperties properties = new ReviewAgentRuntimeProperties();
        properties.setCliEnabled(true);
        properties.setCliAction("resume");
        properties.setCliProjectId("project-llm-failed");
        properties.setCliHumanReviewNote("operator note");
        RecordingService service = new RecordingService();
        service.resumeProjectResult = resultWithStopReason(ReviewProjectStopReason.LLM_CALL_FAILED);
        PostDraftReviewAgentCommandLineRunner runner = new PostDraftReviewAgentCommandLineRunner(service, properties);

        IllegalStateException error = assertThrows(IllegalStateException.class, runner::run);

        assertEquals("project-llm-failed", service.resumedProjectId);
        assertEquals("review agent project run failed: stopReason=llm_call_failed", error.getMessage());
    }

    private static final class RecordingService extends PostDraftReviewAgentService {
        private String reviewedProjectId;
        private String resumedProjectId;
        private String resumedHumanReviewNote;
        private String resetProjectId;
        private String baselineProjectId;
        private String resetFromBaselineProjectId;
        private PostDraftReviewAgentResult reviewProjectResult;
        private PostDraftReviewAgentResult resumeProjectResult;

        private RecordingService() {
            super(
                    new NoOpReader(),
                    new PostDraftReviewSessionFactory(),
                    new PostDraftReviewProblemClassifier(),
                    new PostDraftReviewProcessSummaryAssembler(),
                    request -> request,
                    new NoOpWriter(),
                    null,
                    ReviewSessionStore.noop()
            );
        }

        @Override
        public PostDraftReviewAgentResult reviewProject(StartProjectPostDraftReviewAgentCommand command) {
            this.reviewedProjectId = command.projectId();
            return reviewProjectResult == null ? emptyResult() : reviewProjectResult;
        }

        @Override
        public PostDraftReviewAgentResult resumeProject(String projectId, String humanReviewNote) {
            this.resumedProjectId = projectId;
            this.resumedHumanReviewNote = humanReviewNote;
            return resumeProjectResult == null ? emptyResult() : resumeProjectResult;
        }

        @Override
        public void resetProject(String projectId) {
            this.resetProjectId = projectId;
        }

        @Override
        public void createProjectReviewBaseline(String projectId) {
            this.baselineProjectId = projectId;
        }

        @Override
        public void resetProjectFromBaseline(String projectId) {
            this.resetFromBaselineProjectId = projectId;
        }

        private PostDraftReviewAgentResult emptyResult() {
            return new PostDraftReviewAgentResult(
                    "",
                    new ReviewProcessSummary("project-1", ReviewFocus.forChunk("chunk-1"), ReviewStrategy.LIGHT_EDIT, Set.of(), List.of(), "test"),
                    Optional.empty()
            );
        }
    }

    private static PostDraftReviewAgentResult resultWithStopReason(ReviewProjectStopReason stopReason) {
        return new PostDraftReviewAgentResult(
                "",
                new ReviewProcessSummary(
                        "project-1",
                        ReviewFocus.forChunk("chunk-1"),
                        ReviewStrategy.LIGHT_EDIT,
                        Set.of(),
                        List.of(),
                        "completedChunkCount=0, pendingChunkCount=1, openIssueCount=0, stopReason="
                                + stopReason.name().toLowerCase(java.util.Locale.ROOT)
                ),
                Optional.empty()
        );
    }

    private static final class NoOpWriter implements PostDraftReviewAgentWriter {
        @Override
        public PostDraftReviewAgentResult writeCompleted(String finalTranslatedText, ReviewProcessSummary processSummary) {
            return new PostDraftReviewAgentResult(finalTranslatedText, processSummary, Optional.empty());
        }

        @Override
        public PostDraftReviewAgentResult writeHumanRequired(io.quillloom.application.postdraft.review.model.HumanReviewRequest request) {
            return new PostDraftReviewAgentResult("", request.processSummary(), Optional.of(request));
        }
    }

    private static final class NoOpReader implements PostDraftReviewAgentReader {
        @Override
        public io.quillloom.domain.postdraft.PostDraftContinuationContext loadContinuationContext(String projectId, io.quillloom.application.postdraft.review.model.ReviewFocus focus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<io.quillloom.domain.postdraft.PostDraftChunkRecord> readContinuousChunks(String projectId, String chunkId, io.quillloom.application.postdraft.review.model.ReviewReadDirection direction, int steps) {
            return List.of();
        }

        @Override
        public List<io.quillloom.domain.postdraft.PostDraftChunkRecord> expandByBlock(String projectId, String chunkId) {
            return List.of();
        }

        @Override
        public List<io.quillloom.domain.translation.TranslationDecisionNote> readDecisionNotes(String projectId, String chunkId) {
            return List.of();
        }

        @Override
        public Optional<io.quillloom.domain.translation.ChunkTransitionNote> readTransitionNote(String projectId, String chunkId) {
            return Optional.empty();
        }

        @Override
        public List<io.quillloom.domain.knowledge.KnowledgeCard> lookupKnowledgeCards(String projectId, String chunkId, List<String> queryTerms) {
            return List.of();
        }

        @Override
        public List<io.quillloom.domain.postdraft.PostDraftChunkRecord> readAdjacentChunks(String projectId, String chunkId, int before, int after) {
            return List.of();
        }

        @Override
        public List<io.quillloom.domain.postdraft.PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword) {
            return List.of();
        }

        @Override
        public List<String> listChunkIdsByProject(String projectId) {
            return List.of();
        }

        @Override
        public Optional<io.quillloom.domain.postdraft.PostDraftChunkRecord> loadChunkById(String projectId, String chunkId) {
            return Optional.empty();
        }

        @Override
        public Map<String, String> readConfirmedTerms(String projectId, List<String> sourceTerms) {
            return Map.of();
        }
    }
}
