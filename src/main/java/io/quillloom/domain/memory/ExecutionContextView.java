package io.quillloom.domain.memory;

import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.translation.TranslationCandidateUpdate;

import java.util.List;
import java.util.Map;

/**
 * Agent D 执行层可消费的结构化上下文视图。
 */
public record ExecutionContextView(
        Map<String, String> confirmedTerms,
        List<TranslationCandidateUpdate> candidateTermUpdates,
        LocalSourceContext localSourceContext,
        CoarseBlockContext coarseBlockContext,
        DraftStageGlobalGlossary draftStageGlobalGlossary,
        GlobalAliasConsistencyTable globalAliasConsistencyTable,
        List<KnowledgeCard> relatedKnowledgeCards,
        List<GlobalConstraint> activeConstraints,
        List<String> continuityNotes
) {

    public ExecutionContextView {
        confirmedTerms = confirmedTerms == null ? Map.of() : Map.copyOf(confirmedTerms);
        candidateTermUpdates = candidateTermUpdates == null ? List.of() : List.copyOf(candidateTermUpdates);
        draftStageGlobalGlossary = draftStageGlobalGlossary == null ? DraftStageGlobalGlossary.empty() : draftStageGlobalGlossary;
        globalAliasConsistencyTable = globalAliasConsistencyTable == null ? GlobalAliasConsistencyTable.empty() : globalAliasConsistencyTable;
        relatedKnowledgeCards = relatedKnowledgeCards == null ? List.of() : List.copyOf(relatedKnowledgeCards);
        activeConstraints = activeConstraints == null ? List.of() : List.copyOf(activeConstraints);
        continuityNotes = continuityNotes == null ? List.of() : List.copyOf(continuityNotes);
    }

    public ExecutionContextView(Map<String, String> confirmedTerms,
                                LocalSourceContext localSourceContext,
                                List<KnowledgeCard> relatedKnowledgeCards,
                                List<GlobalConstraint> activeConstraints,
                                List<String> continuityNotes) {
        this(confirmedTerms, List.of(), localSourceContext, CoarseBlockContext.empty(), DraftStageGlobalGlossary.empty(), GlobalAliasConsistencyTable.empty(), relatedKnowledgeCards, activeConstraints, continuityNotes);
    }

    public ExecutionContextView(Map<String, String> confirmedTerms,
                                List<TranslationCandidateUpdate> candidateTermUpdates,
                                LocalSourceContext localSourceContext,
                                CoarseBlockContext coarseBlockContext,
                                List<KnowledgeCard> relatedKnowledgeCards,
                                List<GlobalConstraint> activeConstraints,
                                List<String> continuityNotes) {
        this(confirmedTerms, candidateTermUpdates, localSourceContext, coarseBlockContext, DraftStageGlobalGlossary.empty(), GlobalAliasConsistencyTable.empty(), relatedKnowledgeCards, activeConstraints, continuityNotes);
    }
}
