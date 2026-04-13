package io.quillloom.infrastructure.preprocess.chunksegmentation;

import io.quillloom.application.preprocess.model.ChunkSegmentationTaskInput;
import io.quillloom.infrastructure.preprocess.ParagraphView;
import org.springframework.stereotype.Component;

/**
 * 将 coarse block 内的细分块任务渲染为中文提示词。
 * 模型只负责在段落视图上选择 chunk 边界，不直接返回最终 chunk 文本。
 */
@Component
public class ChunkSegmentationPromptRenderer {

    public String render(ChunkSegmentationTaskInput input) {
        ParagraphView paragraphView = input == null || input.coarseChunkBlock() == null
                ? ParagraphView.from("")
                : ParagraphView.from(input.coarseChunkBlock().sourceText());

        StringBuilder builder = new StringBuilder();
        builder.append("你是小说预处理阶段的 Agent B 细分块规划助手。\n");
        builder.append("你的任务是在一个 coarse block 内给出最终 chunk 的边界规划。\n");
        builder.append("你不能直接输出切好的 chunk 文本，只能按顺序选择段落边界。\n");
        builder.append("每个边界必须使用 endParagraphIndex，表示当前 chunk 到第几个段落结束。\n");
        builder.append("当前输入已经整理成保留段落结构的段落视图，P1、P2、P3 只是段落编号，不是标题。\n");
        builder.append("不要返回原文片段，不要返回 endAnchor，不要跨编号回退。\n");
        builder.append("最后一个 chunk 也必须显式落到当前 coarse block 的最后一个段落编号。\n");
        builder.append("细分块要按情节推进和局部上下文联系来切，不要仅因为换段就切。\n");
        builder.append("同一连续动作、同一场景观察、同一轮对话往返、同一心理或回忆推进，尽量保留在同一个 chunk。\n");
        builder.append("只有在情节转折、场景切换、明显时间跳跃、空间跳跃、叙事视角切换，或结构性片段需要独立存在时，才切出新的 chunk。\n");
        builder.append("切分时必须同时预估目标语言为中文时的译文体量。单个 chunk 的中文译文通常应控制在约 500 到 2000 字。\n");
        builder.append("若预估明显低于约 500 字，且它不是标题、题词、诗歌、书信抬头、列表项、署名等结构性短片段，通常不应单独切出。\n");
        builder.append("若预估明显高于约 2000 字，应优先在最近的自然语义边界切开，而不是硬拖成超大 chunk。\n\n");

        builder.append("【项目输入】\n");
        builder.append("projectId: ").append(nullToEmpty(input.projectId())).append("\n");
        builder.append("title: ").append(nullToEmpty(input.title())).append("\n");
        builder.append("sourceLanguage: ").append(nullToEmpty(input.sourceLanguage())).append("\n");
        builder.append("targetLanguage: ").append(nullToEmpty(input.targetLanguage())).append("\n\n");

        builder.append("【全书分析】\n");
        if (input.bookAnalysis() != null) {
            builder.append("synopsis: ").append(nullToEmpty(input.bookAnalysis().synopsis())).append("\n");
            builder.append("narrativeOutline: ").append(nullToEmpty(input.bookAnalysis().narrativeOutline())).append("\n");
            builder.append("styleProfile: ").append(nullToEmpty(input.bookAnalysis().styleProfile())).append("\n");
        }

        builder.append("\n【全局约束】\n");
        if (input.globalConstraints() == null || input.globalConstraints().isEmpty()) {
            builder.append("- 无\n");
        } else {
            input.globalConstraints().stream().limit(5)
                    .forEach(constraint -> builder.append("- ").append(nullToEmpty(constraint.description())).append("\n"));
        }

        builder.append("\n【当前 coarse block】\n");
        builder.append("blockId: ").append(input.coarseChunkBlock().blockId()).append("\n");
        builder.append("summary: ").append(nullToEmpty(input.coarseChunkBlock().summary())).append("\n");
        builder.append("boundaryHint: ").append(nullToEmpty(input.coarseChunkBlock().boundaryHint())).append("\n");
        builder.append("paragraphCount: ").append(paragraphView.paragraphs().size()).append("\n");
        builder.append("paragraphView:\n").append(paragraphView.renderIndexedView()).append("\n\n");

        builder.append("【输出要求】\n");
        builder.append("1. 只输出一个 JSON 对象，字段只允许有 boundaries。\n");
        builder.append("2. boundaries 按 chunk 顺序排列。\n");
        builder.append("3. 每个元素包含 endParagraphIndex 和 boundaryHint。\n");
        builder.append("4. endParagraphIndex 必须是当前 coarse block 内的合法段号，按顺序递增，最后一个元素必须等于最后一段的编号。\n");
        builder.append("5. boundaryHint 说明为什么在这里切。\n");
        builder.append("6. boundaryHint 必须优先体现局部情节、场景、时间、空间、视角或对话收束等语义边界，不要写成“这一段单独完整”之类的弱理由。\n");
        builder.append("7. 不要输出 Markdown、解释文字或代码块。\n");
        return builder.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
