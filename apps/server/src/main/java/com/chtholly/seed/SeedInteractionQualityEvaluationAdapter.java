package com.chtholly.seed;

import com.chtholly.agent.quality.QualityCriteria;
import com.chtholly.agent.quality.QualityEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Bridges seed interaction quality checks to the generic quality evaluator.
 */
@Component
@RequiredArgsConstructor
public class SeedInteractionQualityEvaluationAdapter implements SeedInteractionQualityEvaluator {

    private final QualityEvaluationService qualityEvaluationService;

    /**
     * Evaluates a generated seed comment while preserving the existing interface.
     *
     * @param account seed account persona.
     * @param context interaction context.
     * @param comment generated comment.
     * @return weighted 1-5 score.
     */
    @Override
    public double evaluate(SeedInteractionAccount account, SeedInteractionContext context, String comment) {
        return qualityEvaluationService.evaluate(
                comment,
                contextFor(account, context),
                QualityCriteria.commentQuality()).score();
    }

    private static String contextFor(SeedInteractionAccount account, SeedInteractionContext context) {
        return """
                账号昵称：%s
                账号人设：%s
                兴趣标签：%s
                帖子标题：%s
                帖子标签：%s
                帖子摘要：%s
                上级评论：%s
                """.formatted(
                account.profile().nickname(),
                account.profile().persona(),
                account.profile().tags(),
                context.postTitle(),
                context.postTags(),
                context.postExcerpt(),
                context.parentCommentContent() == null ? "无" : context.parentCommentContent());
    }
}
