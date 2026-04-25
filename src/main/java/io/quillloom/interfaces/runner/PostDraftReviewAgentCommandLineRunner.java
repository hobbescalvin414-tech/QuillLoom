package io.quillloom.interfaces.runner;

import io.quillloom.application.postdraft.review.command.StartProjectPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentService;
import io.quillloom.infrastructure.postdraft.review.ReviewAgentRuntimeProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "quillloom.postdraft.review.runtime", name = "cli-enabled", havingValue = "true")
public class PostDraftReviewAgentCommandLineRunner implements CommandLineRunner {

    private final PostDraftReviewAgentService service;
    private final ReviewAgentRuntimeProperties properties;

    public PostDraftReviewAgentCommandLineRunner(PostDraftReviewAgentService service,
                                                 ReviewAgentRuntimeProperties properties) {
        this.service = Objects.requireNonNull(service, "service");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void run(String... args) {
        if ("start".equalsIgnoreCase(properties.getCliAction())) {
            failOnBusinessFailure(service.reviewProject(
                    new StartProjectPostDraftReviewAgentCommand(properties.getCliProjectId(), "")
            ));
            return;
        }
        if ("resume".equalsIgnoreCase(properties.getCliAction())) {
            failOnBusinessFailure(service.resumeProject(
                    properties.getCliProjectId(),
                    resolveHumanReviewNote(args)
            ));
            return;
        }
        if ("reset".equalsIgnoreCase(properties.getCliAction())) {
            service.resetProject(properties.getCliProjectId());
            return;
        }
        if ("create-baseline".equalsIgnoreCase(properties.getCliAction())) {
            service.createProjectReviewBaseline(properties.getCliProjectId());
            return;
        }
        if ("reset-from-baseline".equalsIgnoreCase(properties.getCliAction())) {
            service.resetProjectFromBaseline(properties.getCliProjectId());
            return;
        }
        throw new IllegalArgumentException("unsupported cli action: " + properties.getCliAction());
    }

    private void failOnBusinessFailure(PostDraftReviewAgentResult result) {
        if (result == null || result.processSummary() == null) {
            return;
        }
        String processNote = result.processSummary().processNote();
        if (processNote == null || processNote.isBlank()) {
            return;
        }
        if (processNote.contains("stopReason=llm_call_failed")
                || processNote.contains("stopReason=no_progress")
                || processNote.contains("stopReason=wall_clock_timeout")
                || processNote.contains("stopReason=failed")) {
            throw new IllegalStateException("review agent project run failed: " + extractStopReason(processNote));
        }
    }

    private String extractStopReason(String processNote) {
        int index = processNote.indexOf("stopReason=");
        if (index < 0) {
            return processNote;
        }
        int end = processNote.indexOf(',', index);
        return end < 0 ? processNote.substring(index) : processNote.substring(index, end);
    }

    private String resolveHumanReviewNote(String[] args) {
        String fromArgs = findArg(args, "--humanReviewNote=");
        if (fromArgs != null && !fromArgs.isBlank()) {
            return fromArgs;
        }
        if (properties.getCliHumanReviewNote() != null && !properties.getCliHumanReviewNote().isBlank()) {
            return properties.getCliHumanReviewNote();
        }
        throw new IllegalArgumentException("Missing humanReviewNote for resume action");
    }

    static String findArg(String[] args, String prefix) {
        if (args == null) {
            return null;
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    static String requireArg(String[] args, String prefix) {
        String value = findArg(args, prefix);
        if (value != null) {
            return value;
        }
        throw new IllegalArgumentException("Missing command line argument: " + prefix);
    }
}
