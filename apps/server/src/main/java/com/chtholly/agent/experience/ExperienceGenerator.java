package com.chtholly.agent.experience;

import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.auth.event.UserRegisteredEvent;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.event.PostPublishedEvent;
import com.chtholly.post.service.PostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Event-driven experience generation.
 *
 * <p>Creates Chtholly's experiences when notable events happen, even when no user is online.
 */
@Slf4j
@Component
public class ExperienceGenerator {

    private final ExperienceService experienceService;
    private final PostService postService;
    private final TextGenerator textGenerator;

    @Autowired
    public ExperienceGenerator(ExperienceService experienceService,
                               PostService postService,
                               ObjectProvider<ChatClient> chatClientProvider) {
        this(experienceService, postService, prompt -> generateWithChatClient(chatClientProvider.getIfAvailable(), prompt));
    }

    ExperienceGenerator(ExperienceService experienceService,
                        PostService postService,
                        TextGenerator textGenerator) {
        this.experienceService = experienceService;
        this.postService = postService;
        this.textGenerator = textGenerator;
    }

    /**
     * New user registered: Chtholly notices a new guest.
     *
     * @param event user registered event
     */
    @EventListener
    public void onUserRegistered(UserRegisteredEvent event) {
        if (event == null || event.user() == null) {
            return;
        }
        String nickname = event.user().getNickname();
        String experience = textGenerator.generate("""
                你是珂朵莉。有新客人来到了仓库，用第一人称写一句话描述你的感受。
                风格：温和、好奇、不张扬。
                用户名：%s
                """.formatted(nickname == null ? "新客人" : nickname));
        storeIfPresent(experience, 4, "user-registered");
    }

    /**
     * New post published: Chtholly reads it.
     *
     * @param event post published event
     */
    @EventListener
    public void onPostPublished(PostPublishedEvent event) {
        if (event == null) {
            return;
        }
        String title = "新文章";
        try {
            PostDetailResponse detail = postService.getDetail(event.postId(), null);
            if (detail != null && detail.title() != null && !detail.title().isBlank()) {
                title = detail.title();
            }
        } catch (Exception e) {
            log.warn("Load published post detail for experience failed, postId={}: {}", event.postId(), e.getMessage());
        }
        String experience = textGenerator.generate("""
                你是珂朵莉。有人带了一篇新文章来仓库，用第一人称写一句话。
                风格：安静地读完后说一句真心话。
                文章标题：%s
                """.formatted(title));
        storeIfPresent(experience, 3, "post-published");
    }

    /**
     * Community quiet for 3 days: Chtholly feels lonely.
     */
    @Scheduled(cron = "0 0 */6 * * *")
    public void checkCommunityQuietness() {
        long recentPosts = postService.countSince(Duration.ofDays(3));
        if (recentPosts == 0) {
            experienceService.store(new Experience(
                    "已经 3 天没有人带新故事来了……大家都在忙吗？",
                    2,
                    "community-quiet"));
        }
    }

    private void storeIfPresent(String text, int importance, String source) {
        if (text == null || text.isBlank()) {
            return;
        }
        experienceService.store(new Experience(text.trim(), importance, source));
    }

    private static String generateWithChatClient(ChatClient chatClient, String prompt) {
        if (chatClient == null) {
            return "";
        }
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    @FunctionalInterface
    interface TextGenerator {
        String generate(String prompt);
    }
}
