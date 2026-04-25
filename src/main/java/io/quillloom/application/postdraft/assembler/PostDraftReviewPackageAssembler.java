package io.quillloom.application.postdraft.assembler;

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
import io.quillloom.domain.postdraft.PostDraftBlockIndex;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import io.quillloom.domain.preprocess.PersonAliasHint;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.domain.shared.TermTextNormalizer;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.DraftCompilation;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class PostDraftReviewPackageAssembler {

    public static final String PACKAGE_VERSION = "v1";

    public PostDraftReviewPackage assemble(PreprocessDossier dossier,
                                           List<ChunkTranslationDraft> drafts,
                                           ProjectMemorySnapshot projectMemory,
                                           DraftCompilation compilation) {
        if (dossier == null) {
            throw new IllegalArgumentException("dossier must not be null.");
        }
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("drafts must not be empty.");
        }

        List<ChunkAnnotation> chunks = dossier.chunkAnnotations().chunks();
        Map<String, ChunkTranslationDraft> draftByChunkId = new LinkedHashMap<>();
        for (ChunkTranslationDraft draft : drafts) {
            draftByChunkId.put(draft.chunkId(), draft);
        }

        List<PostDraftChunkRecord> chunkRecords = new ArrayList<>();
        for (ChunkAnnotation chunk : chunks) {
            ChunkTranslationDraft draft = draftByChunkId.get(chunk.chunk().chunkId());
            if (draft == null) {
                throw new IllegalArgumentException("Missing draft for chunkId=" + chunk.chunk().chunkId());
            }
            chunkRecords.add(new PostDraftChunkRecord(
                    chunk.chunk().chunkId(),
                    chunk.chunk().sequence(),
                    chunk.chunk().coarseBlockId(),
                    chunk.chunk().sourceText(),
                    draft.translatedText(),
                    null,
                    draft.translatorCommentary(),
                    draft.decisionNotes(),
                    draft.confirmedTermUpdates(),
                    draft.candidateUpdates(),
                    draft.transitionNote()
            ));
        }

        PostDraftTermState termState = buildTermState(projectMemory, drafts);

        return new PostDraftReviewPackage(
                dossier.project().projectId(),
                PACKAGE_VERSION,
                dossier.project().sourceLanguage(),
                dossier.project().targetLanguage(),
                digestSourceDocument(chunks),
                Instant.now(),
                chunkRecords,
                buildBlockIndexes(dossier),
                termState,
                buildGlossarySnapshot(termState),
                buildAliasSnapshot(dossier),
                compilation == null ? "" : compilation.mergedDraft()
        );
    }

    public PostDraftTermState buildTermState(ProjectMemorySnapshot projectMemory,
                                             List<ChunkTranslationDraft> drafts) {
        Map<String, ConfirmedTermEntry> confirmed = new LinkedHashMap<>();
        if (projectMemory != null) {
            projectMemory.confirmedTerms().forEach((sourceTerm, targetTerm) ->
                    mergeConfirmedTermOrThrow(confirmed, sourceTerm, targetTerm, "projectMemory"));
        }
        for (ChunkTranslationDraft draft : drafts) {
            draft.confirmedTermUpdates().forEach((sourceTerm, targetTerm) ->
                    mergeConfirmedTermOrThrow(confirmed, sourceTerm, targetTerm, "chunkId=" + draft.chunkId()));
        }

        Map<String, TranslationCandidateUpdate> candidates = new LinkedHashMap<>();
        if (projectMemory != null) {
            for (TranslationCandidateUpdate update : safeCandidateUpdates(projectMemory.candidateTermUpdates())) {
                candidates.put(candidateKey(update), update);
            }
        }
        for (ChunkTranslationDraft draft : drafts) {
            for (TranslationCandidateUpdate update : safeCandidateUpdates(draft.candidateUpdates())) {
                candidates.putIfAbsent(candidateKey(update), update);
            }
        }

        return new PostDraftTermState(confirmedTermDisplayMap(confirmed), List.copyOf(candidates.values()));
    }

    private Map<String, String> confirmedTermDisplayMap(Map<String, ConfirmedTermEntry> entries) {
        Map<String, String> display = new LinkedHashMap<>();
        entries.values().forEach(entry -> display.put(entry.displaySourceTerm(), entry.displayTargetTerm()));
        return Map.copyOf(display);
    }

    private void mergeConfirmedTermOrThrow(Map<String, ConfirmedTermEntry> confirmedTerms,
                                           String sourceTerm,
                                           String targetTerm,
                                           String evidence) {
        String displaySourceTerm = TermTextNormalizer.displayText(sourceTerm);
        String displayTargetTerm = TermTextNormalizer.displayText(targetTerm);
        String sourceKey = TermTextNormalizer.keyText(displaySourceTerm);
        String targetKey = TermTextNormalizer.keyText(displayTargetTerm);
        if (sourceKey.isBlank() || targetKey.isBlank()) {
            return;
        }
        ConfirmedTermEntry existing = confirmedTerms.get(sourceKey);
        if (existing == null) {
            confirmedTerms.put(sourceKey, new ConfirmedTermEntry(displaySourceTerm, displayTargetTerm, targetKey));
            return;
        }
        if (existing.targetKey().equals(targetKey)) {
            return;
        }
        throw new IllegalStateException(
                "confirmed_term_conflict: sourceTerm=%s, existing=%s, incoming=%s, evidence=%s"
                        .formatted(displaySourceTerm, existing.displayTargetTerm(), displayTargetTerm, evidence)
        );
    }

    private List<PostDraftBlockIndex> buildBlockIndexes(PreprocessDossier dossier) {
        List<PostDraftBlockIndex> blockIndexes = new ArrayList<>();
        Map<String, List<String>> chunkIdsByBlock = new LinkedHashMap<>();
        for (ChunkAnnotation chunk : dossier.chunkAnnotations().chunks()) {
            chunkIdsByBlock.computeIfAbsent(chunk.chunk().coarseBlockId(), ignored -> new ArrayList<>())
                    .add(chunk.chunk().chunkId());
        }
        for (CoarseChunkBlock block : dossier.globalAnalysis().coarseChunkPlan().blocks()) {
            blockIndexes.add(new PostDraftBlockIndex(
                    block.blockId(),
                    block.summary(),
                    chunkIdsByBlock.getOrDefault(block.blockId(), List.of())
            ));
        }
        return List.copyOf(blockIndexes);
    }

    public DraftStageGlobalGlossary buildGlossarySnapshot(PostDraftTermState termState) {
        List<GlossaryEntry> hardEntries = new ArrayList<>();
        termState.effectiveConfirmedTerms().forEach((sourceTerm, targetTerm) -> hardEntries.add(new GlossaryEntry(
                sourceTerm,
                targetTerm,
                GlossaryEntryStrength.HARD,
                GlossaryEntrySourceKind.CONFIRMED_TERM,
                List.of("post-draft:effectiveConfirmedTerms"),
                "初稿完成后的稳定译名基线"
        )));

        List<GlossaryEntry> softEntries = new ArrayList<>();
        for (TranslationCandidateUpdate update : termState.effectiveCandidateTerms()) {
            softEntries.add(new GlossaryEntry(
                    update.sourceTerm(),
                    update.candidateTranslation(),
                    GlossaryEntryStrength.SOFT,
                    GlossaryEntrySourceKind.CANDIDATE_TERM,
                    List.of("post-draft:effectiveCandidateTerms"),
                    update.rationale()
            ));
        }
        return new DraftStageGlobalGlossary(
                List.copyOf(hardEntries),
                List.copyOf(softEntries),
                Map.of(
                        "hardEntryCount", hardEntries.size(),
                        "softEntryCount", softEntries.size()
                )
        );
    }

    private GlobalAliasConsistencyTable buildAliasSnapshot(PreprocessDossier dossier) {
        ProjectKnowledgeBase knowledgeBase = dossier.knowledgeEnrichment().projectKnowledgeBase();
        List<AliasCluster> clusters = new ArrayList<>();
        int index = 1;
        for (ChunkAnnotation chunk : dossier.chunkAnnotations().chunks()) {
            for (PersonAliasHint hint : chunk.personAliasHints()) {
                List<String> surfaceForms = dedup(hint.surfaceForms());
                if (surfaceForms.size() < 2) {
                    continue;
                }
                clusters.add(new AliasCluster(
                        "chunk-hint-" + index++,
                        surfaceForms,
                        surfaceForms.get(0),
                        parseHintAliasState(hint.confidence()),
                        normalize(hint.confidence()),
                        List.of(normalize(hint.evidence())),
                        ""
                ));
            }
        }
        for (KnowledgeCard card : knowledgeBase.cards()) {
            List<String> surfaceForms = dedup(stringList(card.metadata().get("surfaceForms")));
            if (surfaceForms.size() < 2) {
                continue;
            }
            clusters.add(new AliasCluster(
                    "knowledge-card-" + index++,
                    surfaceForms,
                    stringValue(card.metadata().get("canonicalName")),
                    parseAliasState(stringValue(card.metadata().get("aliasState"))),
                    stringValue(card.metadata().get("confidence")),
                    List.of("knowledge-card:" + card.cardId()),
                    ""
            ));
        }
        return new GlobalAliasConsistencyTable(
                List.copyOf(clusters),
                List.of(),
                Map.of("clusterCount", clusters.size())
        );
    }

    private String digestSourceDocument(List<ChunkAnnotation> chunks) {
        StringBuilder builder = new StringBuilder();
        for (ChunkAnnotation chunk : chunks) {
            builder.append(chunk.chunk().chunkId())
                    .append('\n')
                    .append(chunk.chunk().sourceText())
                    .append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(builder.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available.", exception);
        }
    }

    private AliasClusterState parseHintAliasState(String confidence) {
        String normalized = normalize(confidence).toUpperCase(Locale.ROOT);
        if ("HIGH".equals(normalized)) {
            return AliasClusterState.SUSPECTED_ALIAS;
        }
        return AliasClusterState.OBSERVED;
    }

    private AliasClusterState parseAliasState(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return AliasClusterState.OBSERVED;
        }
        try {
            return AliasClusterState.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return AliasClusterState.OBSERVED;
        }
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

    private String stringValue(Object value) {
        return value instanceof String stringValue ? stringValue.trim() : "";
    }

    private List<String> dedup(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private List<TranslationCandidateUpdate> safeCandidateUpdates(List<TranslationCandidateUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return List.of();
        }
        List<TranslationCandidateUpdate> sanitized = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        for (TranslationCandidateUpdate update : updates) {
            if (update == null) {
                continue;
            }
            String sourceTerm = TermTextNormalizer.displayText(update.sourceTerm());
            String candidateTranslation = TermTextNormalizer.displayText(update.candidateTranslation());
            if (sourceTerm.isBlank() || candidateTranslation.isBlank()) {
                continue;
            }
            String key = TermTextNormalizer.pairKey(sourceTerm, candidateTranslation);
            if (dedup.add(key)) {
                sanitized.add(new TranslationCandidateUpdate(
                        sourceTerm,
                        candidateTranslation,
                        update.rationale() == null ? "" : update.rationale().trim(),
                        update.requiresReview()
                ));
            }
        }
        return List.copyOf(sanitized);
    }

    private String candidateKey(TranslationCandidateUpdate update) {
        return TermTextNormalizer.pairKey(update.sourceTerm(), update.candidateTranslation());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record ConfirmedTermEntry(
            String displaySourceTerm,
            String displayTargetTerm,
            String targetKey
    ) {
    }
}
