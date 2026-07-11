package com.chtholly.agent.cognitive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import com.chtholly.agent.comment.CommentGenerationService;
import com.chtholly.agent.learning.InsightService;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Periodic cognitive loop that turns recent site activity into Chtholly observations.
 */
@Slf4j
@Service
@ConditionalOnExpression("${agent.extensions.learning.enabled:true} && ${agent.extensions.experience.enabled:true}")
public class CognitiveEngine {

    private static final Duration RECENT_POST_WINDOW = Duration.ofHours(6);
    private static final Duration MIN_TRIGGER_INTERVAL = Duration.ofMinutes(30);
    private static final int MAX_STORED_THOUGHTS = 3;
    private static final double VALUE_THRESHOLD = 0.7;

    private final PostService postService;
    @SuppressWarnings("unused")
    private final InsightService insightService;
    private final ExperienceService experienceService;
    private final ObservationGenerator observationGenerator;
    private final ObjectProvider<CommentGenerationService> commentGenerationServiceProvider;
    private final Clock clock;
    private volatile Instant lastTriggeredAt = Instant.EPOCH;

    @Autowired
    public CognitiveEngine(PostService postService,
                           InsightService insightService,
                           ExperienceService experienceService,
                           ObjectProvider<CommentGenerationService> commentGenerationServiceProvider,
                           ObjectProvider<ChatClient> chatClientProvider,
                           ObjectMapper objectMapper) {
        this(postService,
                insightService,
                experienceService,
                commentGenerationServiceProvider,
                input -> generateWithChatClient(chatClientProvider.getIfAvailable(), objectMapper, input),
                Clock.systemUTC());
    }

    CognitiveEngine(PostService postService,
                    InsightService insightService,
                    ExperienceService experienceService,
                    ObjectProvider<CommentGenerationService> commentGenerationServiceProvider,
                    ObservationGenerator observationGenerator,
                    Clock clock) {
        this.postService = postService;
        this.insightService = insightService;
        this.experienceService = experienceService;
        this.commentGenerationServiceProvider = commentGenerationServiceProvider;
        this.observationGenerator = observationGenerator;
        this.clock = clock;
    }

    /**
     * Scheduled cognitive cycle. A tiny random delay prevents synchronized work after deploys.
     */
    @Scheduled(cron = "0 */30 * * * *")
    public void scheduledCognitiveCycle() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(0, 5_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        cognitiveCycle();
    }

    /**
     * Runs one cognitive loop: scan posts, generate thoughts, gate by value, and store experiences.
     */
    public void cognitiveCycle() {
        List<PostSummary> newPosts = safeRecentPosts();
        List<String> unresolved = replayRecentConversations();
        List<Observation> thoughts = generateThoughts(newPosts, unresolved);
        List<Observation> valuable = applyValueGate(thoughts);
        if (!valuable.isEmpty()) {
            experienceService.storeExperiences(valuable);
        }
        triggerCommentGeneration();
    }

    private void triggerCommentGeneration() {
        CommentGenerationService commentGenerationService = commentGenerationServiceProvider.getIfAvailable();
        if (commentGenerationService == null) {
            return;
        }
        try {
            commentGenerationService.generateComments();
        } catch (Exception e) {
            log.warn("Cognitive comment generation failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs a cycle only if the previous trigger was more than 30 minutes ago.
     *
     * @return {@code true} if a cycle was run.
     */
    public synchronized boolean triggerIfDue() {
        Instant now = clock.instant();
        if (Duration.between(lastTriggeredAt, now).compareTo(MIN_TRIGGER_INTERVAL) < 0) {
            return false;
        }
        lastTriggeredAt = now;
        cognitiveCycle();
        return true;
    }

    private List<PostSummary> safeRecentPosts() {
        try {
            List<PostSummary> posts = postService.getRecentPosts(RECENT_POST_WINDOW);
            return posts == null ? List.of() : posts;
        } catch (Exception e) {
            log.warn("Cognitive recent post scan failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<String> replayRecentConversations() {
        return List.of();
    }

    private List<Observation> generateThoughts(List<PostSummary> posts, List<String> unresolved) {
        try {
            CognitiveInput input = new CognitiveInput(posts, unresolved, clock.instant());
            List<Observation> generated = observationGenerator.generate(input);
            return generated == null ? List.of() : generated;
        } catch (Exception e) {
            log.warn("Cognitive thought generation failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<Observation> applyValueGate(List<Observation> thoughts) {
        if (thoughts == null || thoughts.isEmpty()) {
            return List.of();
        }
        return thoughts.stream()
                .filter(thought -> thought != null && thought.valueScore() > VALUE_THRESHOLD)
                .filter(this::isCharacterConsistent)
                .limit(MAX_STORED_THOUGHTS)
                .toList();
    }

    private boolean isCharacterConsistent(Observation thought) {
        if (thought == null || !StringUtils.hasText(thought.text())) {
            return false;
        }
        String text = thought.text().trim();
        if (text.contains("作为AI") || text.contains("AI助手") || text.contains("高频运营")) {
            return false;
        }
        return text.contains("我") || text.contains("嗯") || text.contains("呢");
    }

    private static List<Observation> generateWithChatClient(ChatClient chatClient,
                                                            ObjectMapper objectMapper,
                                                            CognitiveInput input) {
        if (chatClient == null) {
            return List.of();
        }
        String prompt = """
                你是珂朵莉。根据以下信息，用第一人称写 2-3 句你的想法。
                风格：安静、温和、有自己的观点。
                只输出 JSON 数组，每项包含 text 和 valueScore（0.0-1.0）。

                最近的新文章：%s
                未完成的对话话题：%s
                """.formatted(formatPosts(input.posts()), formatUnresolved(input.unresolved()));
        try {
            String output = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            if (!StringUtils.hasText(output)) {
                return List.of();
            }
            List<ObservationDraft> drafts = objectMapper.readValue(output, new TypeReference<>() {
            });
            Instant now = input.now();
            return drafts.stream()
                    .filter(draft -> draft != null && StringUtils.hasText(draft.text()))
                    .map(draft -> new Observation(draft.text().trim(), draft.valueScore(), now, "cognitive-cycle"))
                    .toList();
        } catch (Exception e) {
            log.warn("Cognitive LLM generation failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String formatPosts(List<PostSummary> posts) {
        if (posts == null || posts.isEmpty()) {
            return "（暂无）";
        }
        return posts.stream()
                .map(post -> "%s：%s".formatted(post.title(), post.description() == null ? "" : post.description()))
                .toList()
                .toString();
    }

    private static String formatUnresolved(List<String> unresolved) {
        return unresolved == null || unresolved.isEmpty() ? "（暂无）" : unresolved.toString();
    }

    @FunctionalInterface
    interface ObservationGenerator {
        List<Observation> generate(CognitiveInput input);
    }

    record CognitiveInput(List<PostSummary> posts, List<String> unresolved, Instant now) {
    }

    private record ObservationDraft(String text, double valueScore) {
    }
}
