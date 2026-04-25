package io.quillloom.interfaces.api;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.workflow.service.NovelTranslationWorkflowService;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.interfaces.api.dto.PreprocessRequest;
import io.quillloom.interfaces.api.dto.PreprocessResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/preprocess")
public class PreprocessController {

    private final NovelTranslationWorkflowService workflowService;

    public PreprocessController(NovelTranslationWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping
    public PreprocessResponse preprocess(@RequestBody PreprocessRequest request) {
        PreprocessBookCommand command = new PreprocessBookCommand(
                request.projectId(),
                request.title(),
                request.sourceText(),
                request.sourceLanguage(),
                request.targetLanguage()
        );
        PreprocessDossier dossier = workflowService.runPreprocess(command);
        return PreprocessResponse.from(dossier);
    }
}
