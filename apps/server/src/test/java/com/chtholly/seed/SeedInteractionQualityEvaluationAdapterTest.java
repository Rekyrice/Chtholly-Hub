package com.chtholly.seed;

import com.chtholly.agent.quality.QualityCriteria;
import com.chtholly.agent.quality.QualityEvaluationService;
import com.chtholly.agent.quality.QualityResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SeedInteractionQualityEvaluationAdapterTest {

    @Test
    void delegatesCommentEvaluationToGenericQualityService() {
        AtomicReference<QualityCriteria> criteriaRef = new AtomicReference<>();
        AtomicReference<String> contextRef = new AtomicReference<>();
        QualityEvaluationService service = (content, context, criteria) -> {
            criteriaRef.set(criteria);
            contextRef.set(context);
            return new QualityResult(4.1, "评论接住了帖子上下文。", true, Map.of("内容相关性", 4.0));
        };
        SeedInteractionQualityEvaluationAdapter adapter = new SeedInteractionQualityEvaluationAdapter(service);

        double score = adapter.evaluate(account(), context(), "我同意前面那条评论，也想补充一点 Rust 错误处理的取舍。");

        assertThat(score).isEqualTo(4.1);
        assertThat(criteriaRef.get()).isEqualTo(QualityCriteria.commentQuality());
        assertThat(contextRef.get()).contains("账号人设：理性技术党", "帖子标题：Rust CLI 重写记录");
    }

    private static SeedInteractionAccount account() {
        return new SeedInteractionAccount(
                7L,
                new SeedAccountProfile(
                        "yukino",
                        "Yukino",
                        "后端工程师",
                        "/avatar.png",
                        "SECRET",
                        LocalDate.of(2000, 1, 1),
                        "仓库大学",
                        List.of("技术文章", "编程"),
                        "理性技术党"));
    }

    private static SeedInteractionContext context() {
        return new SeedInteractionContext(
                42L,
                "Rust CLI 重写记录",
                List.of("Rust", "CLI"),
                "记录一次命令行工具重写。",
                null,
                "前面那条评论提到了错误处理。",
                2);
    }
}
