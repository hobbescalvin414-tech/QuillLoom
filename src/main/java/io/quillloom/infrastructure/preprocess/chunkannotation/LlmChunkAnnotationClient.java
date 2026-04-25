package io.quillloom.infrastructure.preprocess.chunkannotation;

/**
 * 面向真实模型调用的最小客户端抽象。
 * 当前约束“给定提示词，返回结构化结果”；若实现方能提供原始响应，也可通过 detailed 接口上报。
 */
public interface LlmChunkAnnotationClient {

    ChunkAnnotationLlmResult generate(String prompt);

    default ChunkAnnotationLlmClientResponse generateDetailed(String prompt) {
        return new ChunkAnnotationLlmClientResponse(null, generate(prompt));
    }
}