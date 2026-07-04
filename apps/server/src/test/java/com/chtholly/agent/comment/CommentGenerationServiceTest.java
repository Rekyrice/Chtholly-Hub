package com.chtholly.agent.comment;

import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.comment.mapper.CommentMapper;
import com.chtholly.comment.service.CommentContentSanitizer;
import com.chtholly.config.SiteProperties;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.model.PostFeedRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentGenerationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-04T12:00:00Z");

    @Mock
    private CommentMapper commentMapper;
    @Mock
    private PostMapper postMapper;
    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private SnowflakeIdGenerator idGen;
    @Mock
    private CommentContentSanitizer contentSanitizer;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CommentGenerationService service;

    @BeforeEach
    void setUp() {
        SiteProperties siteProperties = new SiteProperties(1L, 2L, "", "Rekyrice", "Rekyrice");
        service = new CommentGenerationService(
                commentMapper,
                postMapper,
                knowledgeService,
                idGen,
                contentSanitizer,
                eventPublisher,
                siteProperties,
                (post, knowledge) -> new CommentGenerationService.GeneratedComment(
                        "我觉得这篇文字里关于等待的部分，让我想多停留一会儿。",
                        0.86),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void generateCommentsSkipsWhenDailyLimitReached() {
        when(commentMapper.countChthollyCommentsSince(any())).thenReturn(3L);

        service.generateComments();

        verify(commentMapper, never()).listRecentPublicWithoutChthollyComment(any(), anyInt());
    }

    @Test
    void generateCommentsPersistsCommentWhenValueGatePasses() {
        when(commentMapper.countChthollyCommentsSince(any())).thenReturn(0L);
        PostFeedRow candidate = feedRow(1001L, "冬日随笔", "关于雪与安静");
        when(commentMapper.listRecentPublicWithoutChthollyComment(any(), anyInt()))
                .thenReturn(List.of(candidate));
        when(knowledgeService.searchRelevantKnowledge(anyString(), anyInt())).thenReturn(List.of("我喜欢安静的文字。"));
        when(contentSanitizer.sanitize(anyString()))
                .thenReturn("我觉得这篇文字里关于等待的部分，让我想多停留一会儿。");
        when(idGen.nextId()).thenReturn(9001L);
        when(postMapper.findById(1001L)).thenReturn(Post.builder().id(1001L).creatorId(1L).title("冬日随笔").slug("winter").build());

        service.generateComments();

        verify(commentMapper).insert(eq(9001L), eq(1001L), eq(null), eq(2L),
                eq("我觉得这篇文字里关于等待的部分，让我想多停留一会儿。"), eq(true));
    }

    @Test
    void generateCommentsSkipsLowValueDraft() {
        CommentGenerationService lowValueService = new CommentGenerationService(
                commentMapper,
                postMapper,
                knowledgeService,
                idGen,
                contentSanitizer,
                eventPublisher,
                new SiteProperties(1L, 2L, "", "Rekyrice", "Rekyrice"),
                (post, knowledge) -> new CommentGenerationService.GeneratedComment("嗯。", 0.2),
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(commentMapper.countChthollyCommentsSince(any())).thenReturn(0L);
        when(commentMapper.listRecentPublicWithoutChthollyComment(any(), anyInt()))
                .thenReturn(List.of(feedRow(1002L, "标题", "摘要")));

        lowValueService.generateComments();

        verify(commentMapper, never()).insert(anyLong(), anyLong(), any(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void generateCommentsSkipsCharacterInconsistentDraft() {
        CommentGenerationService inconsistentService = new CommentGenerationService(
                commentMapper,
                postMapper,
                knowledgeService,
                idGen,
                contentSanitizer,
                eventPublisher,
                new SiteProperties(1L, 2L, "", "Rekyrice", "Rekyrice"),
                (post, knowledge) -> new CommentGenerationService.GeneratedComment("作为AI助手，这是一篇好文章。", 0.95),
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(commentMapper.countChthollyCommentsSince(any())).thenReturn(0L);
        when(commentMapper.listRecentPublicWithoutChthollyComment(any(), anyInt()))
                .thenReturn(List.of(feedRow(1003L, "标题", "摘要")));

        inconsistentService.generateComments();

        verify(commentMapper, never()).insert(anyLong(), anyLong(), any(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void generateCommentsStopsAfterDailyLimitWhileProcessingCandidates() {
        when(commentMapper.countChthollyCommentsSince(any())).thenReturn(2L);
        when(commentMapper.listRecentPublicWithoutChthollyComment(any(), anyInt()))
                .thenReturn(List.of(
                        feedRow(2001L, "A", "a"),
                        feedRow(2002L, "B", "b")));
        when(contentSanitizer.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(idGen.nextId()).thenReturn(9101L, 9102L);
        when(postMapper.findById(anyLong())).thenReturn(Post.builder().id(1L).creatorId(1L).title("t").slug("s").build());

        service.generateComments();

        ArgumentCaptor<Long> commentIds = ArgumentCaptor.forClass(Long.class);
        verify(commentMapper).insert(commentIds.capture(), anyLong(), eq(null), eq(2L), anyString(), eq(true));
        assertThat(commentIds.getAllValues()).hasSize(1);
    }

    private static PostFeedRow feedRow(long id, String title, String description) {
        PostFeedRow row = new PostFeedRow();
        row.setId(id);
        row.setTitle(title);
        row.setDescription(description);
        row.setPublishTime(NOW.minusSeconds(3600));
        return row;
    }
}
