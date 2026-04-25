package io.quillloom.application.postdraft.assembler;

import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import org.springframework.stereotype.Component;

@Component
public class PostDraftContinuationContextAssembler {

    public PostDraftContinuationContext assemble(PostDraftReviewPackage reviewPackage,
                                                 ProjectKnowledgeBase knowledgeBase) {
        return new PostDraftContinuationContext(
                reviewPackage.projectId(),
                reviewPackage.chunks(),
                reviewPackage.blockIndexes(),
                reviewPackage.termState(),
                reviewPackage.glossarySnapshot(),
                reviewPackage.aliasSnapshot(),
                reviewPackage.mergedDraftText(),
                knowledgeBase
        );
    }
}
