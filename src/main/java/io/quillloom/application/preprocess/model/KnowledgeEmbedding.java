package io.quillloom.application.preprocess.model;

import java.util.List;

/**
 * 知识卡 embedding 结果。
 */
public record KnowledgeEmbedding(
        List<Float> vector,
        String model,
        String version
) {

    public KnowledgeEmbedding {
        vector = vector == null ? List.of() : List.copyOf(vector);
        model = model == null ? "" : model;
        version = version == null ? "" : version;
    }

    public boolean isEmpty() {
        return vector.isEmpty();
    }
}
