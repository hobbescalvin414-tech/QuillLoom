package io.quillloom.domain.preprocess;

/**
 * 稳定的 chunk 身份、来源粗分块标识与原文切片元数据。
 */
public record ChunkDescriptor(
        String chunkId,
        int sequence,
        String coarseBlockId,
        int startOffset,
        int endOffset,
        String sourceText
) {

    public ChunkDescriptor(String chunkId,
                           int sequence,
                           int startOffset,
                           int endOffset,
                           String sourceText) {
        this(chunkId, sequence, null, startOffset, endOffset, sourceText);
    }
}