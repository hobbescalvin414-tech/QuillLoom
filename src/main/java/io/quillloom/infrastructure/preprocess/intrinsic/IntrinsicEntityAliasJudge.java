package io.quillloom.infrastructure.preprocess.intrinsic;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IntrinsicEntityAliasJudge {

    public IntrinsicEntityMergeDecision judge(String canonicalName, String alias, List<String> evidences) {
        List<String> safeEvidences = evidences == null ? List.of() : evidences;
        if (safeEvidences.contains("explicit-renaming")) {
            return IntrinsicEntityMergeDecision.confirmed();
        }
        if (safeEvidences.contains("same-chunk-alias-hint")) {
            return IntrinsicEntityMergeDecision.suspected();
        }
        return IntrinsicEntityMergeDecision.observed();
    }
}
