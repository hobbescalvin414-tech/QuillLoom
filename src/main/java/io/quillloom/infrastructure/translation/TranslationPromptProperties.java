package io.quillloom.infrastructure.translation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "quillloom.translation.chunk-translation.prompt")
public class TranslationPromptProperties {

    private final Global global = new Global();
    private final DraftRound draftRound = new DraftRound();
    private final RevisionRound revisionRound = new RevisionRound();

    public Global getGlobal() {
        return global;
    }

    public DraftRound getDraftRound() {
        return draftRound;
    }

    public RevisionRound getRevisionRound() {
        return revisionRound;
    }

    public static class Global {
        private String accuracyPolicy = "准确、忠实、自然、克制优先。";
        private List<String> styleWarnings = List.of(
                "不要追逐华丽辞藻，不要为了显得优美而主动改写语义。",
                "正文不得混入知识卡说明、术语说明或解释性补写。"
        );

        public String getAccuracyPolicy() {
            return accuracyPolicy;
        }

        public void setAccuracyPolicy(String accuracyPolicy) {
            this.accuracyPolicy = accuracyPolicy;
        }

        public List<String> getStyleWarnings() {
            return styleWarnings;
        }

        public void setStyleWarnings(List<String> styleWarnings) {
            this.styleWarnings = styleWarnings == null ? List.of() : List.copyOf(styleWarnings);
        }
    }

    public static class DraftRound {
        private List<String> coreInstructions = List.of(
                "先完成忠实、自然的当前 chunk 目标语言初稿。",
                "正文只翻译原文已有内容，不要加入括号注、百科说明或知识卡内容。",
                "已生效译名必须沿用；未确认但需要暂时统一的名字，可写入 confirmedTermUpdates。"
        );

        public List<String> getCoreInstructions() {
            return coreInstructions;
        }

        public void setCoreInstructions(List<String> coreInstructions) {
            this.coreInstructions = coreInstructions == null ? List.of() : List.copyOf(coreInstructions);
        }
    }

    public static class RevisionRound {
        private List<String> coreInstructions = List.of(
                "本轮优先修正正文边界问题与明显失真，不要追求更华丽的表达。",
                "根据问题清单移除解释性插入、知识卡泄漏和术语说明腔。",
                "在不改变原意的前提下收紧正文，并继续沿用已生效译名。"
        );

        public List<String> getCoreInstructions() {
            return coreInstructions;
        }

        public void setCoreInstructions(List<String> coreInstructions) {
            this.coreInstructions = coreInstructions == null ? List.of() : List.copyOf(coreInstructions);
        }
    }
}
