package io.quillloom.application.translation.assembler;

import io.quillloom.domain.knowledge.CandidateTerm;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.memory.AliasCluster;
import io.quillloom.domain.memory.AliasClusterState;
import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.memory.GlossaryEntry;
import io.quillloom.domain.memory.GlossaryEntrySourceKind;
import io.quillloom.domain.memory.GlossaryEntryStrength;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.PersonAliasHint;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class GlobalNamingStageAssembler {

    public GlobalNamingStageResult assemble(ChunkAnnotation chunk,
                                            ProjectMemorySnapshot projectMemory,
                                            ProjectKnowledgeBase knowledgeBase,
                                            List<KnowledgeCard> selectedCards) {
        List<GlossaryEntry> hardEntries = buildHardEntries(projectMemory);
        List<GlossaryEntry> softEntries = buildSoftEntries(projectMemory, knowledgeBase, selectedCards);
        DraftStageGlobalGlossary glossary = new DraftStageGlobalGlossary(
                hardEntries,
                softEntries,
                Map.of(
                        "hardEntryCount", hardEntries.size(),
                        "softEntryCount", softEntries.size()
                )
        );
        GlobalAliasConsistencyTable aliasTable = buildAliasConsistencyTable(chunk, knowledgeBase);
        return new GlobalNamingStageResult(glossary, aliasTable);
    }

    private List<GlossaryEntry> buildHardEntries(ProjectMemorySnapshot projectMemory) {
        if (projectMemory == null || projectMemory.confirmedTerms().isEmpty()) {
            return List.of();
        }
        List<GlossaryEntry> entries = new ArrayList<>();
        projectMemory.confirmedTerms().forEach((source, target) -> entries.add(new GlossaryEntry(
                source,
                target,
                GlossaryEntryStrength.HARD,
                GlossaryEntrySourceKind.CONFIRMED_TERM,
                List.of("project-memory:confirmedTerms"),
                "稳定 confirmed 术语"
        )));
        return List.copyOf(entries);
    }

    private List<GlossaryEntry> buildSoftEntries(ProjectMemorySnapshot projectMemory,
                                                 ProjectKnowledgeBase knowledgeBase,
                                                 List<KnowledgeCard> selectedCards) {
        Map<String, GlossaryEntry> entries = new LinkedHashMap<>();
        Set<String> confirmedKeys = new LinkedHashSet<>();
        if (projectMemory != null) {
            projectMemory.confirmedTerms().forEach((source, target) -> confirmedKeys.add(entryKey(source, target)));
            for (TranslationCandidateUpdate update : projectMemory.candidateTermUpdates()) {
                addSoftEntry(entries, confirmedKeys, new GlossaryEntry(
                        update.sourceTerm(),
                        update.candidateTranslation(),
                        GlossaryEntryStrength.SOFT,
                        GlossaryEntrySourceKind.CANDIDATE_TERM,
                        List.of("project-memory:candidateTermUpdates"),
                        update.rationale()
                ));
            }
        }
        if (knowledgeBase != null) {
            for (CandidateTerm candidateTerm : knowledgeBase.candidateTerms()) {
                if (candidateTerm.candidateTranslations().isEmpty()) {
                    continue;
                }
                addSoftEntry(entries, confirmedKeys, new GlossaryEntry(
                        candidateTerm.sourceTerm(),
                        candidateTerm.candidateTranslations().get(0),
                        GlossaryEntryStrength.SOFT,
                        GlossaryEntrySourceKind.CANDIDATE_TERM,
                        List.of("knowledge-base:candidateTerms"),
                        candidateTerm.rationale()
                ));
            }
        }
        for (KnowledgeCard card : safeCards(selectedCards)) {
            Object recommendedTranslation = card.metadata().get("recommendedTranslation");
            String sourceTerm = firstNonBlank(card.anchorNames());
            if (!(recommendedTranslation instanceof String targetTerm) || sourceTerm.isBlank() || targetTerm.isBlank()) {
                continue;
            }
            addSoftEntry(entries, confirmedKeys, new GlossaryEntry(
                    sourceTerm,
                    targetTerm,
                    GlossaryEntryStrength.SOFT,
                    GlossaryEntrySourceKind.KNOWLEDGE_CARD_DERIVED,
                    List.of("knowledge-card:" + card.cardId()),
                    "来自已选知识卡的显式推荐译法"
            ));
        }
        return List.copyOf(entries.values());
    }

    private void addSoftEntry(Map<String, GlossaryEntry> entries,
                              Set<String> confirmedKeys,
                              GlossaryEntry entry) {
        String sourceTerm = trim(entry.sourceTerm());
        String targetTerm = trim(entry.targetTerm());
        if (sourceTerm.isBlank() || targetTerm.isBlank()) {
            return;
        }
        String key = entryKey(sourceTerm, targetTerm);
        if (confirmedKeys.contains(key)) {
            return;
        }
        entries.putIfAbsent(key, new GlossaryEntry(
                sourceTerm,
                targetTerm,
                entry.entryStrength(),
                entry.sourceKind(),
                entry.evidenceRefs(),
                entry.notes()
        ));
    }

    private GlobalAliasConsistencyTable buildAliasConsistencyTable(ChunkAnnotation chunk,
                                                                   ProjectKnowledgeBase knowledgeBase) {
        List<AliasCluster> clusters = new ArrayList<>();
        if (chunk != null) {
            int index = 1;
            for (PersonAliasHint hint : chunk.personAliasHints()) {
                if (hint.surfaceForms().size() < 2) {
                    continue;
                }
                clusters.add(new AliasCluster(
                        "chunk-hint-" + index++,
                        dedup(hint.surfaceForms()),
                        hint.surfaceForms().get(0),
                        normalizeAliasState(hint.confidence()),
                        trim(hint.confidence()),
                        List.of(trim(hint.evidence())),
                        ""
                ));
            }
        }
        if (knowledgeBase != null) {
            int index = 1;
            for (KnowledgeCard card : knowledgeBase.cards()) {
                List<String> surfaceForms = stringList(card.metadata().get("surfaceForms"));
                if (surfaceForms.size() < 2) {
                    continue;
                }
                clusters.add(new AliasCluster(
                        "knowledge-card-" + index++,
                        dedup(surfaceForms),
                        stringValue(card.metadata().get("canonicalName")),
                        parseAliasState(stringValue(card.metadata().get("aliasState"))),
                        stringValue(card.metadata().get("confidence")),
                        List.of("knowledge-card:" + card.cardId()),
                        ""
                ));
            }
        }
        return new GlobalAliasConsistencyTable(
                List.copyOf(clusters),
                List.of(),
                Map.of("clusterCount", clusters.size())
        );
    }

    private AliasClusterState normalizeAliasState(String confidence) {
        String normalized = trim(confidence).toUpperCase(Locale.ROOT);
        if ("HIGH".equals(normalized)) {
            return AliasClusterState.SUSPECTED_ALIAS;
        }
        return AliasClusterState.OBSERVED;
    }

    private AliasClusterState parseAliasState(String value) {
        String normalized = trim(value);
        if (normalized.isBlank()) {
            return AliasClusterState.OBSERVED;
        }
        try {
            return AliasClusterState.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return AliasClusterState.OBSERVED;
        }
    }

    private List<KnowledgeCard> safeCards(List<KnowledgeCard> values) {
        return values == null ? List.of() : values;
    }

    private String firstNonBlank(List<String> values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object rawValue : rawValues) {
            if (rawValue instanceof String stringValue && !stringValue.isBlank()) {
                values.add(stringValue.trim());
            }
        }
        return List.copyOf(values);
    }

    private List<String> dedup(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private String stringValue(Object value) {
        return value instanceof String stringValue ? stringValue.trim() : "";
    }

    private String entryKey(String source, String target) {
        return trim(source).toLowerCase(Locale.ROOT) + "=>" + trim(target).toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
