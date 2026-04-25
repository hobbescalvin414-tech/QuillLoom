package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmKnowledgeNeedPlanner implements KnowledgeNeedPlanner {

    private final KnowledgeNeedPlanningPromptRenderer promptRenderer;
    private final LlmKnowledgeNeedPlannerClient client;
    private final KnowledgeNeedPlanningResultParser parser;

    public LlmKnowledgeNeedPlanner(KnowledgeNeedPlanningPromptRenderer promptRenderer,
                                   LlmKnowledgeNeedPlannerClient client,
                                   KnowledgeNeedPlanningResultParser parser) {
        this.promptRenderer = promptRenderer;
        this.client = client;
        this.parser = parser;
    }

    @Override
    public List<KnowledgeNeed> plan(ChunkAnnotation chunk) {
        return plan(chunk, "");
    }

    @Override
    public List<KnowledgeNeed> plan(ChunkAnnotation chunk, String targetLanguage) {
        String prompt = promptRenderer.render(chunk, targetLanguage);
        String response = client.generate(prompt);
        return parser.parse(chunk, response);
    }
}
