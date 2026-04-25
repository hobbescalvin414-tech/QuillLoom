package io.quillloom.infrastructure.preprocess;

import java.util.List;

public record KnowledgeNeedPlanningResult(
        List<KnowledgeNeedPlanningItem> needs
) {

    public record KnowledgeNeedPlanningItem(
            boolean shouldSearch,
            String needKind,
            String signalSource,
            String searchIntent,
            String coverageKey,
            String cardType,
            String queryText,
            List<String> anchorNames,
            List<String> keywords,
            List<String> originRefs,
            String reason,
            int priority
    ) {
    }
}
