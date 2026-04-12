package io.quillloom.infrastructure.translation;

import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.memory.CoarseBlockContext;
import io.quillloom.domain.memory.LocalSourceContext;
import io.quillloom.domain.preprocess.PersonAliasHint;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationTaskInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 将稳定执行输入渲染为 Agent D 的目标语言 prompt。
 * 当前支持单 chunk 两轮执行：初稿轮与修订轮。
 */
@Component
public class TranslationPromptRenderer {

    private final TranslationPromptProperties properties;

    public TranslationPromptRenderer() {
        this(new TranslationPromptProperties());
    }

    @Autowired
    public TranslationPromptRenderer(TranslationPromptProperties properties) {
        this.properties = properties;
    }

    public String render(TranslationTaskInput input) {
        return renderDraftRound(input);
    }

    public String renderDraftRound(TranslationTaskInput input) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 QuillLoom 的 Agent D，负责受控流水线中的单个 chunk 翻译执行。\n");
        builder.append("当前是第 1 轮：初稿生成轮。\n");
        builder.append("你的任务是基于稳定执行输入，为当前 chunk 生成结构化翻译初稿。\n");
        builder.append("本轮重点是先产出忠实、自然、可用的初稿，并记录未决问题、候选译法、必要的衔接提示；若知识卡仍不足，可显式请求本地知识库补卡。\n\n");

        appendGlobalPolicy(builder);

        appendStableInput(builder, input);

        builder.append("【本轮要求】\n");
        appendConfiguredInstructions(builder, properties.getDraftRound().getCoreInstructions());
        builder.append("- 当前生效译名表中的译名必须优先沿用，不能改写。\n");
        builder.append("- 对于尚未进入当前生效译名表的人名、地名、称谓、专名，应优先选择一个当前全文先统一沿用的译名，并写入 confirmedTermUpdates。\n");
        builder.append("- 如果同一 source term 还有其他可能译法，其他可能译法继续放入 candidateUpdates，不影响当前生效译名。\n");
        builder.append("- 若当前生效译名与你判断不同，不要覆盖，只能写入 decisionNotes 或 candidateUpdates。\n");
        builder.append("- 正文不得写入知识卡内容、背景解释、括号注或百科式补充。\n");
        builder.append("- 不要为显得华丽、诗性或优美而主动改写语义。\n");
        builder.append("- 正文默认必须使用目标语言；若需保留原文专名、引文或称谓，必须以源文真实出现为依据，不得额外混入第三语言解释。\n");
        builder.append("- translatorCommentary 只说明本轮翻译处理策略与取舍，不要写未决问题、术语更新、候选译法或前后 chunk 衔接提示。\n");
        builder.append("- decisionNotes 只记录未决问题、风险或需要人工复核的点，不要写成流程指令。\n");
        builder.append("- transitionNote 只记录与前后 chunk 的衔接提示，不要写术语治理、分块调整方案或流程指令。\n");
        builder.append("- 若不需要边界调整，boundaryAdjustmentSuggested 返回 false。\n");
        builder.append("- 若判断当前知识卡不足以支撑修订，可额外返回 knowledgeLookupRequest，请求本地知识库补卡；请求必须具体，不要笼统写“更多背景”。\n\n");

        appendOutputContract(builder, input, true);
        return builder.toString();
    }

    public String renderRevisionRound(TranslationTaskInput input, ChunkTranslationLlmResult previousRoundResult) {
        return renderRevisionRound(input, previousRoundResult, List.of());
    }

    public String renderRevisionRound(TranslationTaskInput input,
                                      ChunkTranslationLlmResult previousRoundResult,
                                      List<TranslatedTextIssue> textIssues) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 QuillLoom 的 Agent D，负责受控流水线中的单个 chunk 翻译执行。\n");
        builder.append("当前是第 2 轮：修订检查轮。\n");
        builder.append("你的任务不是从头重翻，而是基于第 1 轮结果，对当前 chunk 做检查、修订和收口。\n\n");

        appendGlobalPolicy(builder);

        appendStableInput(builder, input);

        builder.append("【第 1 轮结果】\n");
        builder.append("初稿译文：\n").append(nullToEmpty(previousRoundResult.translatedText())).append("\n\n");
        builder.append("初稿处理说明：").append(nullToEmpty(previousRoundResult.translatorCommentary())).append("\n");
        appendDecisionNotes(builder, previousRoundResult.decisionNotes());
        appendConfirmedTermUpdates(builder, previousRoundResult.confirmedTermUpdates());
        appendCandidateUpdates(builder, previousRoundResult.candidateUpdates());
        appendTransitionNote(builder, previousRoundResult.transitionNote());
        builder.append("\n");

        appendTextIssues(builder, textIssues);
        builder.append("【本轮要求】\n");
        appendConfiguredInstructions(builder, properties.getRevisionRound().getCoreInstructions());
        builder.append("- 本轮不是重翻，不是自由润色，而是按 issue 清单定向修订。\n");
        builder.append("- 优先修正 target-language-purity、active glossary 合规和 text-boundary-warning 相关问题。\n");
        builder.append("- 若 issue 清单与第 1 轮 decisionNotes 存在冲突，以当前问题清单为准逐项修正。\n");
        builder.append("- 基于第 1 轮结果做修订，不要重写任务目标。\n");
        builder.append("- 优先修正文句不稳、术语不一致、衔接不顺，以及正文边界污染问题。\n");
        builder.append("- 收敛 decisionNotes，只保留真正尚未解决的问题。\n");
        builder.append("- 当前生效译名表中的译名在第 2 轮仍然只能遵守，不能改写。\n");
        builder.append("- confirmedTermUpdates 仍只允许补充输入中不存在的当前生效译名。\n");
        builder.append("- 若上一轮某项还不够确定，应继续保留在 candidateUpdates，而不是强行覆盖当前生效译名。\n");
        builder.append("- translatorCommentary 只保留本轮修订采用的处理说明，不要夹带未决问题、术语更新、补卡请求或衔接提示。\n");
        builder.append("- decisionNotes 只保留真正尚未解决的风险和问题。\n");
        builder.append("- transitionNote 只保留前后 chunk 的衔接提示，不要写术语治理或重新切分建议。\n");
        builder.append("- 第 2 轮不要再返回 knowledgeLookupRequest。\n\n");

        appendOutputContract(builder, input, false);
        return builder.toString();
    }

    private void appendStableInput(StringBuilder builder, TranslationTaskInput input) {
        builder.append("【项目】\n");
        builder.append("项目ID：").append(input.sourceMaterial().project().projectId()).append("\n");
        builder.append("书名：").append(input.sourceMaterial().project().title()).append("\n");
        builder.append("源语言：").append(input.sourceMaterial().project().sourceLanguage()).append("\n");
        builder.append("目标语言：").append(input.sourceMaterial().project().targetLanguage()).append("\n\n");

        builder.append("【全书理解】\n");
        if (input.sourceMaterial().bookAnalysis() != null) {
            builder.append("全书概要：").append(nullToEmpty(input.sourceMaterial().bookAnalysis().synopsis())).append("\n");
            builder.append("叙事结构：").append(nullToEmpty(input.sourceMaterial().bookAnalysis().narrativeOutline())).append("\n");
            builder.append("风格画像：").append(nullToEmpty(input.sourceMaterial().bookAnalysis().styleProfile())).append("\n");
            appendList(builder, "翻译策略提示", input.sourceMaterial().bookAnalysis().translationStrategyNotes(), 6);
        }
        builder.append("\n");

        builder.append("【当前 chunk】\n");
        builder.append("chunkId：").append(input.sourceMaterial().chunk().chunk().chunkId()).append("\n");
        builder.append("sequence：").append(input.sourceMaterial().chunk().chunk().sequence()).append("\n");
        builder.append("chunk 摘要：").append(nullToEmpty(input.sourceMaterial().chunk().summary())).append("\n");
        appendList(builder, "实体", input.sourceMaterial().chunk().entities(), 12);
        appendList(builder, "背景问题", input.sourceMaterial().chunk().backgroundQuestions(), 8);
        appendList(builder, "翻译风险", input.sourceMaterial().chunk().translationRisks(), 8);
        appendList(builder, "关键表达", input.sourceMaterial().chunk().keyExpressions(), 12);
        builder.append("原文：\n").append(nullToEmpty(input.sourceMaterial().chunk().chunk().sourceText())).append("\n\n");

        builder.append("【局部上下文】\n");
        appendLocalSourceContext(builder, input.executionContextView().localSourceContext());
        builder.append("\n");

        builder.append("【粗分块上下文】\n");
        appendCoarseBlockContext(builder, input.executionContextView().coarseBlockContext());
        builder.append("\n");

        builder.append("【当前生效译名表】\n");
        appendConfirmedTerms(builder, input.executionContextView().confirmedTerms());
        appendProjectCandidateTerms(builder, input.executionContextView().candidateTermUpdates());
        appendPersonAliasHints(builder, input.sourceMaterial().chunk().personAliasHints());
        appendList(builder, "连续性提示", input.executionContextView().continuityNotes(), 8);
        builder.append("全局约束：\n");
        if (input.executionContextView().activeConstraints().isEmpty()) {
            builder.append("- 无\n");
        } else {
            input.executionContextView().activeConstraints().stream()
                    .limit(8)
                    .forEach(constraint -> builder.append("- ").append(nullToEmpty(constraint.description())).append("\n"));
        }
        builder.append("\n");

        builder.append("【相关知识卡】\n");
        appendKnowledgeCards(builder, input.executionContextView().relatedKnowledgeCards(), input.runtimeOptions().allowKnowledgeCards());
        builder.append("\n");

        builder.append("【运行要求】\n");
        builder.append("保留段落：").append(input.runtimeOptions().preserveParagraphBreaks() ? "是" : "否").append("\n");
        builder.append("输出衔接提示：").append(input.runtimeOptions().emitHandoffNotes() ? "是" : "否").append("\n\n");
    }

    private void appendOutputContract(StringBuilder builder, TranslationTaskInput input, boolean allowKnowledgeLookupRequest) {
        builder.append("只输出一个 JSON 对象，不要输出 Markdown，不要输出解释，不要输出代码块。\n");
        if (allowKnowledgeLookupRequest) {
            builder.append("JSON 必须严格包含以下字段：translatedText、translatorCommentary、decisionNotes、confirmedTermUpdates、candidateUpdates、transitionNote。若需要本地知识库补卡，可额外返回 knowledgeLookupRequest。\n");
        } else {
            builder.append("JSON 必须严格包含以下字段：translatedText、translatorCommentary、decisionNotes、confirmedTermUpdates、candidateUpdates、transitionNote。不要返回 knowledgeLookupRequest。\n");
        }
        builder.append("字段要求如下：\n");
        builder.append("1. translatedText：字符串，给出当前 chunk 的")
                .append(describeTargetLanguage(input))
                .append("翻译草稿。\n");
        builder.append("2. translatorCommentary：字符串，简述本次翻译的处理策略与取舍；不要写未决问题、术语更新、候选译名、补卡请求或前后 chunk 衔接提示。\n");
        builder.append("3. decisionNotes：数组。每项包含 type、sourceAnchor、description、recommendation，用于记录未决问题或风险；没有可返回空数组。\n");
        builder.append("4. confirmedTermUpdates：数组。每项包含 sourceTerm、translatedTerm。它表示当前初稿阶段生效译名的增量；只允许新增输入中不存在的生效译名，不允许改写已有生效译名；没有可返回空数组。\n");
        builder.append("5. candidateUpdates：数组。每项包含 sourceTerm、candidateTranslation、rationale、requiresReview，用于记录同一 source term 的其他候选译法；这些候选不会覆盖当前生效译名；没有可返回空数组。\n");
        builder.append("6. transitionNote：对象，包含 previousChunkConnection、nextChunkConnection、boundaryAdjustmentSuggested，只用于提示与前后 chunk 的衔接；不要写术语决策、分块调整方案或流程指令。\n");
        if (allowKnowledgeLookupRequest) {
            builder.append("7. knowledgeLookupRequest：仅第 1 轮在知识不足时可选返回。该对象包含 reason、queryTerms、requestedTypes、anchors、limit。reason 必须是受控缺口类型；queryTerms 必须具体；requestedTypes 必须是希望优先命中的知识卡类型；limit 最大为 3。\n");
        }
    }

    private void appendGlobalPolicy(StringBuilder builder) {
        builder.append("【翻译总原则】\n");
        builder.append(nullToEmpty(properties.getGlobal().getAccuracyPolicy())).append("\n");
        appendConfiguredInstructions(builder, properties.getGlobal().getStyleWarnings());
        builder.append("\n");
    }

    private void appendConfiguredInstructions(StringBuilder builder, List<String> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            return;
        }
        instructions.stream()
                .filter(item -> item != null && !item.isBlank())
                .forEach(item -> builder.append("- ").append(item.trim()).append("\n"));
    }

    private String describeTargetLanguage(TranslationTaskInput input) {
        String targetLanguage = nullToEmpty(input.sourceMaterial().project().targetLanguage()).trim();
        if (targetLanguage.isBlank()) {
            return "目标语言";
        }
        return "目标语言（" + targetLanguage + "）";
    }

    private void appendDecisionNotes(StringBuilder builder, List<ChunkTranslationDecisionNoteResult> values) {
        builder.append("第 1 轮 decisionNotes：\n");
        if (values == null || values.isEmpty()) {
            builder.append("- 无\n");
            return;
        }
        values.forEach(value -> builder.append("- [")
                .append(nullToEmpty(value.type()))
                .append("] ")
                .append(nullToEmpty(value.sourceAnchor()))
                .append(" | ")
                .append(nullToEmpty(value.description()))
                .append(" | 建议：")
                .append(nullToEmpty(value.recommendation()))
                .append("\n"));
    }

    private void appendConfirmedTermUpdates(StringBuilder builder, List<ConfirmedTermUpdateResult> values) {
        builder.append("第 1 轮 confirmedTermUpdates：\n");
        if (values == null || values.isEmpty()) {
            builder.append("- 无\n");
            return;
        }
        values.forEach(value -> builder.append("- ")
                .append(nullToEmpty(value.sourceTerm()))
                .append(" => ")
                .append(nullToEmpty(value.translatedTerm()))
                .append("\n"));
    }

    private void appendCandidateUpdates(StringBuilder builder, List<ChunkTranslationCandidateUpdateResult> values) {
        builder.append("第 1 轮 candidateUpdates：\n");
        if (values == null || values.isEmpty()) {
            builder.append("- 无\n");
            return;
        }
        values.forEach(value -> builder.append("- ")
                .append(nullToEmpty(value.sourceTerm()))
                .append(" => ")
                .append(nullToEmpty(value.candidateTranslation()))
                .append(" | 理由：")
                .append(nullToEmpty(value.rationale()))
                .append(" | requiresReview=")
                .append(value.requiresReview())
                .append("\n"));
    }

    private void appendTransitionNote(StringBuilder builder, ChunkTranslationTransitionNoteResult value) {
        builder.append("第 1 轮 transitionNote：\n");
        if (value == null) {
            builder.append("- 无\n");
            return;
        }
        builder.append("- previousChunkConnection：").append(nullToEmpty(value.previousChunkConnection())).append("\n");
        builder.append("- nextChunkConnection：").append(nullToEmpty(value.nextChunkConnection())).append("\n");
        builder.append("- boundaryAdjustmentSuggested：").append(value.boundaryAdjustmentSuggested()).append("\n");
    }

    private void appendLocalSourceContext(StringBuilder builder, LocalSourceContext context) {
        appendList(builder, "前文原文窗口", context.previousChunkSourceTexts(), 4);
        appendList(builder, "前文译文窗口", context.previousChunkTranslatedTexts(), 4);
        appendList(builder, "后文原文窗口", context.nextChunkSourceTexts(), 4);
        appendList(builder, "前文摘要窗口", context.previousChunkSummaries(), 6);
        appendList(builder, "后文摘要窗口", context.nextChunkSummaries(), 6);
    }

    private void appendCoarseBlockContext(StringBuilder builder, CoarseBlockContext context) {
        builder.append("当前粗分块ID：").append(nullToEmpty(context.currentBlockId())).append("\n");
        builder.append("当前粗分块摘要：").append(nullToEmpty(context.currentBlockSummary())).append("\n");
        builder.append("当前 chunk 在粗分块内位置：").append(context.chunkIndexInCurrentBlock()).append("/").append(context.chunkCountInCurrentBlock()).append("\n");
        builder.append("是否为粗分块首 chunk：").append(context.firstChunkInCurrentBlock()).append("\n");
        builder.append("是否为粗分块末 chunk：").append(context.lastChunkInCurrentBlock()).append("\n");
        builder.append("上一粗分块ID：").append(nullToEmpty(context.previousBlockId())).append("\n");
        builder.append("上一粗分块摘要：").append(nullToEmpty(context.previousBlockSummary())).append("\n");
        builder.append("下一粗分块ID：").append(nullToEmpty(context.nextBlockId())).append("\n");
        builder.append("下一粗分块摘要：").append(nullToEmpty(context.nextBlockSummary())).append("\n");
    }

    private void appendConfirmedTerms(StringBuilder builder, Map<String, String> confirmedTerms) {
        builder.append("当前初稿阶段生效译名（全文先统一沿用）：\n");
        if (confirmedTerms == null || confirmedTerms.isEmpty()) {
            builder.append("- 无\n");
            return;
        }
        confirmedTerms.forEach((source, translated) ->
                builder.append("- ").append(nullToEmpty(source)).append(" => ").append(nullToEmpty(translated)).append("\n"));
    }

    private void appendProjectCandidateTerms(StringBuilder builder,
                                             List<TranslationCandidateUpdate> values) {
        builder.append("项目候选译名池：\n");
        if (values == null || values.isEmpty()) {
            builder.append("- 无\n");
            return;
        }
        values.stream()
                .limit(12)
                .forEach(value -> builder.append("- ")
                        .append(nullToEmpty(value.sourceTerm()))
                        .append(" => ")
                        .append(nullToEmpty(value.candidateTranslation()))
                        .append(" | 理由：")
                        .append(nullToEmpty(value.rationale()))
                        .append(" | requiresReview=")
                        .append(value.requiresReview())
                        .append("\n"));
    }

    private void appendPersonAliasHints(StringBuilder builder, List<PersonAliasHint> values) {
        builder.append("【人名弱提示】\n");
        builder.append("以下提示仅供参考，不代表已确认事实，不要自动把不同称呼强行合并成同一稳定译名：\n");
        if (values == null || values.isEmpty()) {
            builder.append("- 无\n");
            return;
        }
        values.stream().limit(6).forEach(value -> builder.append("- ")
                .append(String.join(" / ", value.surfaceForms()))
                .append(" | type=")
                .append(nullToEmpty(value.hintType()))
                .append(" | confidence=")
                .append(nullToEmpty(value.confidence()))
                .append(" | evidence=")
                .append(nullToEmpty(value.evidence()))
                .append("\n"));
    }

    private void appendTextIssues(StringBuilder builder, List<TranslatedTextIssue> textIssues) {
        builder.append("【正文问题清单】\n");
        if (textIssues == null || textIssues.isEmpty()) {
            builder.append("- 无\n\n");
            return;
        }
        textIssues.forEach(issue -> builder.append("- ")
                .append(nullToEmpty(issue.code()))
                .append("：")
                .append(nullToEmpty(issue.description()))
                .append("\n"));
        builder.append("\n");
    }

    private void appendKnowledgeCards(StringBuilder builder,
                                      List<KnowledgeCard> cards,
                                      boolean allowKnowledgeCards) {
        if (!allowKnowledgeCards) {
            builder.append("- 当前运行选项禁止注入知识卡\n");
            return;
        }
        if (cards == null || cards.isEmpty()) {
            builder.append("- 无\n");
            return;
        }
        cards.stream().limit(6).forEach(card -> {
            builder.append("- 类型：").append(card.cardType()).append("\n");
            builder.append("  标题：").append(nullToEmpty(card.title())).append("\n");
            builder.append("  内容：").append(nullToEmpty(card.content())).append("\n");
            appendList(builder, "  关键词", card.keywords(), 6);
            appendList(builder, "  锚点", card.anchorNames(), 4);
        });
    }

    private void appendList(StringBuilder builder, String label, List<String> values, int limit) {
        builder.append(label).append("：\n");
        if (values == null || values.isEmpty()) {
            builder.append("- 无\n");
            return;
        }
        values.stream()
                .limit(limit)
                .forEach(value -> builder.append("- ").append(nullToEmpty(value)).append("\n"));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
