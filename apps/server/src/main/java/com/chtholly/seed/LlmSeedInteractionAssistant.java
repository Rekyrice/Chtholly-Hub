package com.chtholly.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
/**
 * LLM-backed generator for seed account discussions.
 */
@Slf4j
@Component
public class LlmSeedInteractionAssistant implements SeedInteractionTextGenerator {

    private final ObjectProvider<ChatClient> chatClientProvider;

    public LlmSeedInteractionAssistant(ObjectProvider<ChatClient> chatClientProvider) {
        this.chatClientProvider = chatClientProvider;
    }

    @Override
    public String generateReply(SeedInteractionAccount account, SeedInteractionContext context) {
        String fallback = fallbackReply(account, context);
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return fallback;
        }

        String prompt = """
                你正在模拟社区里的一个真实种子账号，不要提到 AI。

                账号昵称：%s
                人设：%s
                兴趣标签：%s

                帖子标题：%s
                帖子标签：%s
                帖子摘要：%s
                上级评论：%s

                请写一条 50-150 字的中文评论。要求：
                1. 符合账号人设，不要像客服或通用助手；
                2. 和帖子或上级评论有具体关联；
                3. 如果有上级评论，可以自然地接住、补充或轻微反驳；
                4. 语气像社区用户聊天，不要空泛夸奖。
                """.formatted(
                account.profile().nickname(),
                account.profile().persona(),
                account.profile().tags(),
                context.postTitle(),
                context.postTags(),
                context.postExcerpt(),
                context.parentCommentContent() == null ? "无" : context.parentCommentContent());
        try {
            String generated = chatClient.prompt().user(prompt).call().content();
            return generated == null || generated.isBlank() ? fallback : generated.trim();
        } catch (Exception e) {
            log.warn("Seed interaction reply generation failed, using fallback: {}", e.getMessage());
            return fallback;
        }
    }

    private String fallbackReply(SeedInteractionAccount account, SeedInteractionContext context) {
        String nickname = account.profile().nickname();
        String parent = context.parentCommentContent();
        String title = context.postTitle();
        String persona = account.profile().persona().toLowerCase(Locale.ROOT);

        if (parent != null && !parent.isBlank()) {
            return "我能接上前面那条评论的意思。关于《" + title + "》，我也觉得重点不只是结论，而是作者把过程写出来了，这样读起来会更像真的在交流。";
        }
        if (persona.contains("技术") || persona.contains("编程") || persona.contains("算法")) {
            return "这篇《" + title + "》里最有用的是实践细节。只讲结论的话很容易忘，能把踩坑和取舍写出来，后面的人就少绕一点路。";
        }
        if (persona.contains("番") || persona.contains("动漫")) {
            return "看到《" + title + "》这个角度还挺开心的。比起单纯打分，我更喜欢这种把当时心情也写进去的观后感。";
        }
        if (persona.contains("书") || persona.contains("文学")) {
            return "读《" + title + "》的时候会感觉作者在慢慢整理自己的想法。这样的文字不用很热闹，但会留下一个清楚的回声。";
        }
        return nickname + "觉得《" + title + "》这篇挺适合慢慢看。里面有些细节不是特别显眼，但认真读完会留下印象。";
    }

}
