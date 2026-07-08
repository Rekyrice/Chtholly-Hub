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
                你是「%s」，一个真实的博客作者。
                请写一篇关于「%s」的博客文章，主题要围绕标题自然展开。

                标题：%s
                类型：%s
                标签：%s

                写作要求：
                - 1000-3000 字，Markdown 格式
                - 正文需要有 H2/H3 小标题，至少 2-3 个小标题
                - 需要有开头引入、正文展开、结尾总结
                - 有个人观点和具体例子，语气自然，像在和朋友聊天
                - 技术类文章必须有代码示例，并解释为什么这样写
                - 生活、读书、摄影、番剧类文章要有个人感悟、具体场景或引用
                - 加入一点随机的不完美：口语化表达、偶尔跑题、个人吐槽，但不要错别字
                - 不要完美得像 AI 写的，永远不要提到自己是 AI
                """.formatted(account.persona(), postPlan.title(), postPlan.title(), postPlan.category(), postPlan.tags());
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
