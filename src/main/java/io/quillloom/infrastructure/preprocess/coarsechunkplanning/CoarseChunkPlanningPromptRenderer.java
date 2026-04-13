package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import io.quillloom.application.preprocess.model.CoarseChunkPlanningTaskInput;
import io.quillloom.infrastructure.preprocess.ParagraphView;
import org.springframework.stereotype.Component;

/**
 * 将粗分块规划任务渲染成中文提示词。
 * 模型只负责在段落视图上选择边界，不直接输出最终切块文本。
 */
@Component
public class CoarseChunkPlanningPromptRenderer {

    public String render(CoarseChunkPlanningTaskInput input) {
        ParagraphView paragraphView = ParagraphView.from(input.sourceText());

        StringBuilder builder = new StringBuilder();
        builder.append("你是小说预处理阶段的 Agent A 粗分块规划助手。\n");
        builder.append("你的任务是根据全文的段落视图给出全书级粗分块方案。\n");
        builder.append("你不能直接改写文本，也不能直接输出切好的块文本，只能按顺序选择段落边界。\n");
        builder.append("每个边界必须使用 endParagraphIndex，表示当前粗块到第几个段落结束。\n");
        builder.append("段落视图已经保留段落结构，P1、P2、P3 只是段落编号，不是标题。\n");
        builder.append("不要返回原文片段，不要返回 endAnchor，不要跨编号回退。\n");
        builder.append("最后一个粗块也必须显式落到最后一个段落编号。\n");
        builder.append("粗分块默认要尽量大，优先保证后续块级摘要、块级标注和块级知识抽取有稳定上下文，不要把普通正文切成一串过小 block。\n");
        builder.append("普通正文只有在章节切换、场景切换、明显时间跳跃、空间跳跃、叙事视角切换时才切出新的 coarse block，不能仅因为换段就切。\n");
        builder.append("标题、题词、诗歌、书信抬头、列表项、署名、版权页这类结构性片段可以短块独立；除此之外，能合并就合并。\n");
        builder.append("如果一组相邻段落仍在描述同一连续动作、同一场景或同一叙事单元，必须并入同一个 coarse block。\n\n");

        builder.append("【项目输入】\n");
        builder.append("projectId: ").append(nullToEmpty(input.projectId())).append("\n");
        builder.append("title: ").append(nullToEmpty(input.title())).append("\n");
        builder.append("sourceLanguage: ").append(nullToEmpty(input.sourceLanguage())).append("\n");
        builder.append("targetLanguage: ").append(nullToEmpty(input.targetLanguage())).append("\n");
        builder.append("paragraphCount: ").append(paragraphView.paragraphs().size()).append("\n\n");

        builder.append("【输出要求】\n");
        builder.append("1. 只输出一个 JSON 对象，字段只允许有 boundaries。\n");
        builder.append("2. boundaries 按顺序排列，代表整本书的粗块列表。\n");
        builder.append("3. 每个元素包含 endParagraphIndex、summary、boundaryHint。\n");
        builder.append("4. endParagraphIndex 必须是合法段号，按顺序递增，最后一个元素必须等于最后一段的编号。\n");
        builder.append("5. summary 是该粗块的简短概括。\n");
        builder.append("6. boundaryHint 说明为什么在这里切。\n");
        builder.append("7. block 数量宁少勿多；如果拿不准，应优先合并为更大的 coarse block，而不是提前切碎。\n");
        builder.append("8. 对普通正文，boundaryHint 必须体现章节、场景、时间、空间或视角层面的切分理由，不能写成“这一段单独完整”之类的弱理由。\n");
        builder.append("9. 不要把每个段落都当成一个 coarse block；只有明确属于结构性短片段时，才允许单段独立成块。\n");
        builder.append("10. 不要输出 Markdown，不要解释，不要输出代码块。\n\n");

        builder.append("【paragraphView】\n");
        builder.append(paragraphView.renderIndexedView()).append("\n");
        return builder.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
