package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Default rule-based organizer that condenses external search hits into one structured evidence result.
 */
@Component
public class KnowledgeSearchResultCondenser implements KnowledgeSearchResultOrganizer {

    private static final int MAX_HITS_PER_QUERY = 3;
    private static final int MAX_SNIPPET_LENGTH = 180;

    @Override
    public KnowledgeSearchOrganizationDecision organize(ChunkAnnotation chunk,
                                                        KnowledgeNeed need,
                                                        List<KnowledgeSearchHit> hits) {
        OrganizedKnowledgeEvidence evidence = condense(need, hits);
        if (evidence == null) {
            return KnowledgeSearchOrganizationDecision.rejected(
                    need,
                    hits == null ? 0 : hits.size(),
                    0,
                    "NO_CONDENSED_EVIDENCE",
                    "rule-based condenser could not build evidence"
            );
        }
        return KnowledgeSearchOrganizationDecision.accepted(
                hits == null ? 0 : hits.size(),
                Math.min(hits == null ? 0 : hits.size(), MAX_HITS_PER_QUERY),
                evidence
        );
    }

    public OrganizedKnowledgeEvidence condense(KnowledgeNeed need,
                                               List<KnowledgeSearchHit> hits) {
        if (need == null) {
            return null;
        }
        List<KnowledgeSearchHit> normalizedHits = trimHits(hits);
        if (normalizedHits.isEmpty()) {
            return null;
        }

        Set<String> evidenceUrls = new LinkedHashSet<>();
        Set<String> providers = new LinkedHashSet<>();
        List<String> evidenceLines = new ArrayList<>();
        String title = defaultTitle(need, normalizedHits.get(0));

        int index = 0;
        for (KnowledgeSearchHit hit : normalizedHits) {
            index++;
            if (hit.url() != null && !hit.url().isBlank()) {
                evidenceUrls.add(hit.url().trim());
            }
            if (hit.source() != null && !hit.source().isBlank()) {
                providers.add(hit.source().trim());
            }
            evidenceLines.add(index + ". " + summarizeHit(hit));
        }

        return new OrganizedKnowledgeEvidence(
                need.cardType(),
                title,
                renderTypedContent(need, normalizedHits, evidenceLines),
                List.copyOf(need.anchorNames()),
                List.copyOf(evidenceUrls),
                List.copyOf(need.originRefs()),
                providers.isEmpty() ? "" : String.join(",", providers),
                "MEDIUM"
        );
    }

    private String renderTypedContent(KnowledgeNeed need,
                                      List<KnowledgeSearchHit> hits,
                                      List<String> evidenceLines) {
        String lead = summarizeHit(hits.get(0));
        return switch (need.cardType()) {
            case CHARACTER_PROFILE -> renderCharacterProfile(need, lead, evidenceLines);
            case TERM_EXPLANATION -> renderTermExplanation(need, lead, evidenceLines);
            case SETTING_ENTRY -> renderSettingEntry(need, lead, evidenceLines);
            case CULTURAL_BACKGROUND -> renderBackground(need, lead, evidenceLines, "文化背景");
            case HISTORICAL_BACKGROUND -> renderBackground(need, lead, evidenceLines, "历史背景");
            case IMAGERY -> renderImagery(need, lead, evidenceLines);
        };
    }

    private String renderCharacterProfile(KnowledgeNeed need,
                                          String lead,
                                          List<String> evidenceLines) {
        return "人物线索：围绕“" + need.queryText() + "”整理出以下人物相关信息。\n"
                + "当前可用判断：" + lead + "\n"
                + "翻译关注点：优先保持人物称谓、身份线索和关系提示一致。\n"
                + "证据摘录：\n"
                + String.join("\n", evidenceLines);
    }

    private String renderTermExplanation(KnowledgeNeed need,
                                         String lead,
                                         List<String> evidenceLines) {
        return "术语说明：围绕“" + need.queryText() + "”整理出以下解释。\n"
                + "当前可用解释：" + lead + "\n"
                + "翻译关注点：优先保持术语含义、称谓层级和上下文使用方式稳定。\n"
                + "证据摘录：\n"
                + String.join("\n", evidenceLines);
    }

    private String renderSettingEntry(KnowledgeNeed need,
                                      String lead,
                                      List<String> evidenceLines) {
        return "设定条目：围绕“" + need.queryText() + "”整理出以下设定信息。\n"
                + "当前可用说明：" + lead + "\n"
                + "翻译关注点：优先保留地点、机构、器物或制度的设定语义，不要过度泛化。\n"
                + "证据摘录：\n"
                + String.join("\n", evidenceLines);
    }

    private String renderBackground(KnowledgeNeed need,
                                    String lead,
                                    List<String> evidenceLines,
                                    String label) {
        return label + "：围绕“" + need.queryText() + "”整理出以下背景信息。\n"
                + "当前可用背景：" + lead + "\n"
                + "翻译关注点：优先服务当前 chunk 的背景理解，不要扩写成百科说明。\n"
                + "证据摘录：\n"
                + String.join("\n", evidenceLines);
    }

    private String renderImagery(KnowledgeNeed need,
                                 String lead,
                                 List<String> evidenceLines) {
        return "意象说明：围绕“" + need.queryText() + "”整理出以下意象信息。\n"
                + "当前可用解释：" + lead + "\n"
                + "翻译关注点：优先保留象征意义、修辞功能和情绪色彩。\n"
                + "证据摘录：\n"
                + String.join("\n", evidenceLines);
    }

    private List<KnowledgeSearchHit> trimHits(List<KnowledgeSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<KnowledgeSearchHit> results = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        for (KnowledgeSearchHit hit : hits) {
            if (hit == null || isBlank(hit.title()) || isBlank(hit.snippet())) {
                continue;
            }
            String key = hit.title().trim() + "|" + hit.snippet().trim();
            if (!dedup.add(key)) {
                continue;
            }
            results.add(hit);
            if (results.size() >= MAX_HITS_PER_QUERY) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private String defaultTitle(KnowledgeNeed need,
                                KnowledgeSearchHit firstHit) {
        if (firstHit != null && firstHit.title() != null && !firstHit.title().isBlank()) {
            return firstHit.title().trim();
        }
        return need.queryText();
    }

    private String summarizeHit(KnowledgeSearchHit hit) {
        String snippet = hit.snippet() == null ? "" : hit.snippet().trim();
        if (snippet.length() > MAX_SNIPPET_LENGTH) {
            snippet = snippet.substring(0, MAX_SNIPPET_LENGTH) + "...";
        }
        if (hit.url() == null || hit.url().isBlank()) {
            return snippet;
        }
        return snippet + " [来源: " + hit.url().trim() + "]";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
