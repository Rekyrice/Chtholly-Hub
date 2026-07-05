package com.chtholly.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * LLM-backed text generator with deterministic template fallback.
 */
@Slf4j
@Primary
@Component
public class LlmSeedTextGenerator implements SeedTextGenerator {

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final TemplateSeedTextGenerator fallback;

    public LlmSeedTextGenerator(ObjectProvider<ChatClient> chatClientProvider,
                                TemplateSeedTextGenerator fallback) {
        this.chatClientProvider = chatClientProvider;
        this.fallback = fallback;
    }

    @Override
    public String bangumiReview(BangumiSubjectSeed subject) {
        String prompt = """
                你是珂朵莉，一个喜欢二次元的温柔女孩。请为《%s》写一段推荐语。
                这部作品评分 %.1f，简介：%s。
                请用你的个人感受来推荐，可以说说为什么喜欢、适合什么时候看、和你的心情有什么关系。
                要求：第一人称，100-200 字，温和、克制，不要过度热情。
                """.formatted(subject.title(), subject.score(), subject.summary());
        return generate(prompt, fallback.bangumiReview(subject));
    }

    @Override
    public String postBody(SeedAccountProfile account, SeedPostPlan postPlan) {
        String prompt = """
                请以「%s」的人设写一篇种子文章。
                标题：%s
                类型：%s
                标签：%s
                要求：符合人设，可读，像真实社区用户发布；技术文章包含代码片段；不要提到你是 AI。
                """.formatted(account.persona(), postPlan.title(), postPlan.category(), postPlan.tags());
        return generate(prompt, fallback.postBody(account, postPlan));
    }

    @Override
    public String comment(SeedAccountProfile commenter, SeedAccountProfile author, SeedPostPlan postPlan) {
        String prompt = """
                用户「%s」读了「%s」的文章《%s》。
                请写一条 50-150 字的自然评论，符合评论者人设：%s。
                评论要和文章相关，不要空洞夸奖。
                """.formatted(commenter.nickname(), author.nickname(), postPlan.title(), commenter.persona());
        return generate(prompt, fallback.comment(commenter, author, postPlan));
    }

    private String generate(String prompt, String fallbackText) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return fallbackText;
        }
        try {
            String text = chatClient.prompt().user(prompt).call().content();
            return text == null || text.isBlank() ? fallbackText : text.trim();
        } catch (Exception e) {
            log.warn("Seed text LLM generation failed, using fallback: {}", e.getMessage());
            return fallbackText;
        }
    }
}
