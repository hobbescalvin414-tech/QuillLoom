package io.quillloom.domain.preprocess;

/**
 * Agent A 产出的全书级粗切分块。
 * 它表达的是较大粒度的边界规划，不是最终翻译 chunk。
 */
public record CoarseChunkBlock(
        String blockId,
        int sequence,
        int startOffset,
        int endOffset,
        String sourceText,
        String summary,
        String boundaryHint
) {

    public CoarseChunkBlock(String blockId,
                            int sequence,
                            int startOffset,
                            int endOffset,
                            String sourceText,
                            String boundaryHint) {
        this(blockId, sequence, startOffset, endOffset, sourceText, "", boundaryHint);
    }
}