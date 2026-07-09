package com.chtholly.agent.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicQualityEvaluationServiceTest {

    private final HeuristicQualityEvaluationService service = new HeuristicQualityEvaluationService();

    @Test
    void evaluatesStructuredArticleAboveArticleThreshold() {
        QualityResult result = service.evaluate("""
                ## 开头
                最近我用 Rust 重写了一个 CLI 工具，最大的变化不是性能，而是错误处理终于清楚了。

                ## 实践
                ```rust
                fn main() {
                    println!("hello");
                }
                ```

                ## 总结
                这次经验让我确认，小工具也值得认真整理边界。
                """, "标题：Rust CLI 重写记录\n标签：Rust, CLI", QualityCriteria.articleQuality());

        assertThat(result.score()).isGreaterThanOrEqualTo(3.0);
        assertThat(result.passed()).isTrue();
        assertThat(result.feedback()).isNotBlank();
        assertThat(result.dimensionScores()).containsKeys("内容深度", "可读性", "有价值", "真实感");
    }

    @Test
    void marksBlankContentAsFailed() {
        QualityResult result = service.evaluate("   ", "标题：空文章", QualityCriteria.articleQuality());

        assertThat(result.score()).isLessThan(3.0);
        assertThat(result.passed()).isFalse();
    }
}
