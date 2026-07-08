package com.chtholly.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-backed generator and judge for seed account discussions.
 */
@Slf4j
@Component
public class LlmSeedInteractionAssistant implements SeedInteractionTextGenerator, SeedInteractionQualityEvaluator {

    private static final Pattern SCORE_PATTERN = Pattern.compile("([1-5](?:\\.\\d+)?)");

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

    @Override
    public double evaluate(SeedInteractionAccount account, SeedInteractionContext context, String comment) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return heuristicScore(account, context, comment);
        }

        String prompt = """
                请评估以下社区评论的质量，输出一个 1-5 分的加权总分即可，可以附一句简短理由。

                评分维度：
                - 内容相关性 0.3：是否与帖子或上级评论相关
                - 人设一致性 0.3：是否符合账号性格
                - 趣味性 0.2：是否有一点观点或细节
                - 原创性 0.2：是否不像模板套话

                账号人设：%s
                帖子标题：%s
                帖子标签：%s
                帖子摘要：%s
                上级评论：%s
                待评评论：%s
                """.formatted(
                account.profile().persona(),
                context.postTitle(),
                context.postTags(),
                context.postExcerpt(),
                context.parentCommentContent() == null ? "无" : context.parentCommentContent(),
                comment);
        try {
            String result = chatClient.prompt().user(prompt).call().content();
            return parseScore(result, heuristicScore(account, context, comment));
        } catch (Exception e) {
            log.warn("Seed interaction quality evaluation failed, using heuristic: {}", e.getMessage());
            return heuristicScore(account, context, comment);
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

    private double heuristicScore(SeedInteractionAccount account, SeedInteractionContext context, String comment) {
        if (comment == null || comment.isBlank()) {
            return 0.0;
        }
        double score = 2.8;
        String normalized = comment.toLowerCase(Locale.ROOT);
        if (comment.length() >= 20 && comment.length() <= 180) {
            score += 0.5;
        }
        if (normalized.contains(context.postTitle().toLowerCase(Locale.ROOT))) {
            score += 0.3;
        }
        for (String tag : context.postTags()) {
            if (normalized.contains(tag.toLowerCase(Locale.ROOT))) {
                score += 0.25;
                break;
            }
        }
        if (context.parentCommentContent() != null && (normalized.contains("前面") || normalized.contains("同意") || normalized.contains("补充"))) {
            score += 0.35;
        }
        for (String tag : account.profile().tags()) {
            if (normalized.contains(tag.toLowerCase(Locale.ROOT))) {
                score += 0.2;
                break;
            }
        }
        return Math.min(5.0, score);
    }

    private double parseScore(String text, double fallback) {
        if (text == null) {
            return fallback;
        }
        Matcher matcher = SCORE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
