package io.quillloom.domain.memory;

/**
 * Agent D 可消费的粗分块级上下文视图。
 * 它表达当前 chunk 所属粗分块，以及相邻粗分块与块内位置感信息。
 */
public record CoarseBlockContext(
        String currentBlockId,
        String currentBlockSummary,
        int chunkIndexInCurrentBlock,
        int chunkCountInCurrentBlock,
        boolean firstChunkInCurrentBlock,
        boolean lastChunkInCurrentBlock,
        String previousBlockId,
        String previousBlockSummary,
        String nextBlockId,
        String nextBlockSummary
) {

    public static CoarseBlockContext empty() {
        return new CoarseBlockContext(null, "", 0, 0, false, false, null, "", null, "");
    }
}