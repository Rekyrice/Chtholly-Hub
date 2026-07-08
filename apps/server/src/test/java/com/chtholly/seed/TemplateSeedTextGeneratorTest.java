package com.chtholly.seed;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateSeedTextGeneratorTest {

    private final TemplateSeedTextGenerator generator = new TemplateSeedTextGenerator();

    @Test
    void technicalFallbackBodyIsStructuredLongFormBlogPost() {
        String body = generator.postBody(account("Yukino", List.of("Java", "后端")),
                new SeedPostPlan(
                        "聊聊最近用 Rust 重写一个 CLI 工具的体验",
                        "技术",
                        List.of("技术", "Rust", "CLI"),
                        3,
                        1));

        assertThat(body.length()).isBetween(1000, 3000);
        assertThat(body).startsWith("# 聊聊最近用 Rust 重写一个 CLI 工具的体验");
        assertThat(body).contains("## ");
        assertThat(body).contains("```");
        assertThat(body).doesNotContain("AI");
    }

    @Test
    void essayFallbackBodyIsStructuredLongFormBlogPost() {
        String body = generator.postBody(account("Sakura", List.of("读书", "生活")),
                new SeedPostPlan(
                        "把一段很普通的午后写下来",
                        "生活",
                        List.of("生活", "读书", "随笔"),
                        2,
                        1));

        assertThat(body.length()).isBetween(1000, 3000);
        assertThat(body).startsWith("# 把一段很普通的午后写下来");
        assertThat(body).contains("## ");
        assertThat(body).contains("我");
        assertThat(body).doesNotContain("AI");
    }

    private static SeedAccountProfile account(String nickname, List<String> tags) {
        return new SeedAccountProfile(
                nickname.toLowerCase(),
                nickname,
                "记录一些真实的想法。",
                "/avatar.png",
                "SECRET",
                LocalDate.of(2000, 1, 1),
                "仓库",
                tags,
                nickname + " 是一个真实的博客作者，语气自然，喜欢写具体经历。");
    }
}
