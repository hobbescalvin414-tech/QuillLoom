package io.quillloom.infrastructure.preprocess;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quillloom.application.preprocess.model.KnowledgeEmbedding;
import io.quillloom.application.preprocess.port.out.KnowledgeEmbeddingService;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容 embedding 实现。
 */
public class OpenAiCompatibleKnowledgeEmbeddingService implements KnowledgeEmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final String modelName;

    public OpenAiCompatibleKnowledgeEmbeddingService(EmbeddingModel embeddingModel,
                                                     String modelName) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
    }

    @Override
    public KnowledgeEmbedding embed(String text) {
        if (text == null || text.isBlank()) {
            return new KnowledgeEmbedding(List.of(), modelName, "");
        }
        var response = embeddingModel.embed(text);
        float[] vector = response.content().vector();
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return new KnowledgeEmbedding(List.copyOf(values), modelName, "v1");
    }
}
