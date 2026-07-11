package com.chtholly.agent.comment;

import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.comment.mapper.CommentMapper;
import com.chtholly.comment.model.CommentRow;
import com.chtholly.comment.service.CommentContentSanitizer;
import com.chtholly.config.SiteProperties;
import com.chtholly.notification.event.CommentCreatedEvent;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.model.PostFeedRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 认知循环末尾：为近期文章生成珂朵莉评论（Value Gate + 每日上限）。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.extensions.community-actions", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("${llm.enabled:false}")
public class CommentGenerationService {

    private static final double VALUE_GATE_THRESHOLD = 0.7;
    private static final int MAX_DAILY_COMMENTS = 3;
    private static final Duration CANDIDATE_WINDOW = Duration.ofHours(24);
    private static final int MAX_CANDIDATES = 10;

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final KnowledgeService knowledgeService;
    private final SnowflakeIdGenerator idGen;
    private final CommentContentSanitizer contentSanitizer;
    private final ApplicationEventPublisher eventPublisher;
    private final SiteProperties siteProperties;
    private final CommentDraftGenerator draftGenerator;
    private final Clock clock;

    @Autowired
    public CommentGenerationService(CommentMapper commentMapper,
                                    PostMapper postMapper,
                                    KnowledgeService knowledgeService,
                                    SnowflakeIdGenerator idGen,
                                    CommentContentSanitizer contentSanitizer,
                                    ApplicationEventPublisher eventPublisher,
                                    SiteProperties siteProperties,
                                    ObjectProvider<ChatClient> chatClientProvider,
                                    ObjectMapper objectMapper) {
        this(commentMapper,
                postMapper,
                knowledgeService,
                idGen,
                contentSanitizer,
                eventPublisher,
                siteProperties,
                (post, knowledge) -> generateWithChatClient(
                        chatClientProvider.getIfAvailable(), objectMapper, post, knowledge),
                Clock.systemUTC());
    }

    CommentGenerationService(CommentMapper commentMapper,
                             PostMapper postMapper,
                             KnowledgeService knowledgeService,
                             SnowflakeIdGenerator idGen,
                             CommentContentSanitizer contentSanitizer,
                             ApplicationEventPublisher eventPublisher,
                             SiteProperties siteProperties,
                             CommentDraftGenerator draftGenerator,
                             Clock clock) {
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
        this.knowledgeService = knowledgeService;
        this.idGen = idGen;
        this.contentSanitizer = contentSanitizer;
        this.eventPublisher = eventPublisher;
        this.siteProperties = siteProperties;
        this.draftGenerator = draftGenerator;
        this.clock = clock;
    }

    /**
     * 为近期尚无珂朵莉评论的文章生成评论。
     */
    @Transactional
    public void generateComments() {
        Instant dayStart = LocalDate.now(clock).atStartOfDay(ZoneOffset.UTC).toInstant();
        long todayCount = commentMapper.countChthollyCommentsSince(dayStart);
        if (todayCount >= MAX_DAILY_COMMENTS) {
            return;
        }

        Instant since = clock.instant().minus(CANDIDATE_WINDOW);
        List<PostFeedRow> candidates = commentMapper.listRecentPublicWithoutChthollyComment(
                since, MAX_CANDIDATES);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        long chthollyUserId = siteProperties.chthollyUserId();
        for (PostFeedRow candidate : candidates) {
            if (todayCount >= MAX_DAILY_COMMENTS) {
                break;
            }
            if (candidate == null || candidate.getId() == null) {
                continue;
            }

            GeneratedComment generated = generateComment(candidate);
            if (generated == null) {
                continue;
            }
            if (generated.valueScore() <= VALUE_GATE_THRESHOLD || !isCharacterConsistent(generated.text())) {
                continue;
            }

            String content = contentSanitizer.sanitize(generated.text());
            if (!StringUtils.hasText(content)) {
                continue;
            }

            long postId = candidate.getId();
            long commentId = idGen.nextId();
            commentMapper.insert(commentId, postId, null, chthollyUserId, content, true);
            todayCount++;

            publishCommentCreatedEvent(commentId, postId, chthollyUserId);
        }
    }

    private GeneratedComment generateComment(PostFeedRow post) {
        List<String> knowledge = knowledgeService.searchRelevantKnowledge(
                post.getTitle() == null ? "" : post.getTitle(), 2);
        try {
            return draftGenerator.generate(post, knowledge);
        } catch (Exception e) {
            log.warn("Chtholly comment generation failed postId={}: {}", post.getId(), e.getMessage());
            return null;
        }
    }

    private void publishCommentCreatedEvent(long commentId, long postId, long chthollyUserId) {
        try {
            CommentRow row = commentMapper.findById(commentId);
            Post post = postMapper.findById(postId);
            if (row == null || post == null) {
                return;
            }
            eventPublisher.publishEvent(new CommentCreatedEvent(
                    commentId,
                    postId,
                    null,
                    chthollyUserId,
                    row.getAuthorNickname(),
                    row.getAuthorAvatar(),
                    post.getCreatorId() == null ? 0L : post.getCreatorId(),
                    post.getTitle(),
                    post.getSlug(),
                    null
            ));
        } catch (Exception e) {
            log.warn("Chtholly comment event publish failed commentId={}: {}", commentId, e.getMessage());
        }
    }

    private boolean isCharacterConsistent(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.contains("作为AI") || trimmed.contains("AI助手") || trimmed.contains("好文章")) {
            return false;
        }
        return trimmed.contains("我") || trimmed.contains("嗯") || trimmed.contains("呢");
    }

    private static GeneratedComment generateWithChatClient(ChatClient chatClient,
                                                           ObjectMapper objectMapper,
                                                           PostFeedRow post,
                                                           List<String> knowledge) {
        if (chatClient == null) {
            return null;
        }
        String title = post.getTitle() == null ? "" : post.getTitle();
        String summary = post.getDescription() == null ? "" : post.getDescription();
        String knowledgeText = knowledge == null || knowledge.isEmpty()
                ? "（暂无）"
                : String.join("\n", knowledge);

        String prompt = """
                你是珂朵莉。你刚读完了一篇仓库里的文章，想留下一条评论。

                文章标题：%s
                文章内容摘要：%s
                你的相关知识：%s

                要求：
                - 用第一人称，1-3 句话
                - 风格：安静、真诚、有自己的想法
                - 不要说"好文章"之类的空话，要有具体的感受或想法
                - 不要过度热情，也不要冷漠
                - 如果和文章内容有共鸣，可以提一句自己的经历或感受
                """.formatted(title, summary, knowledgeText);

        try {
            String text = chatClient.prompt().user(prompt).call().content();
            if (!StringUtils.hasText(text)) {
                return null;
            }
            String trimmed = text.trim();

            String evalPrompt = """
                    评估以下珂朵莉的评论质量，只输出 JSON：
                    {
                      "valueScore": 0.0,
                      "characterConsistency": 0.0,
                      "relevance": 0.0
                    }

                    评论：%s
                    文章标题：%s
                    """.formatted(trimmed, title);

            String evalOutput = chatClient.prompt().user(evalPrompt).call().content();
            EvaluationDraft eval = objectMapper.readValue(evalOutput, EvaluationDraft.class);
            double valueScore = (eval.valueScore() + eval.characterConsistency() + eval.relevance()) / 3.0;
            return new GeneratedComment(trimmed, valueScore);
        } catch (Exception e) {
            log.warn("Chtholly comment LLM failed postId={}: {}", post.getId(), e.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    interface CommentDraftGenerator {
        GeneratedComment generate(PostFeedRow post, List<String> knowledge);
    }

    record GeneratedComment(String text, double valueScore) {
    }

    private record EvaluationDraft(double valueScore, double characterConsistency, double relevance) {
    }
}
