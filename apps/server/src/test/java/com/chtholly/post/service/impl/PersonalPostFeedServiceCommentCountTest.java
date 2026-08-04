package com.chtholly.post.service.impl;

import com.chtholly.comment.service.CommentService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.user.service.PublicAuthorQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalPostFeedServiceCommentCountTest {

    @Mock private CounterService counterService;
    @Mock private CommentService commentService;
    @Mock private PublicAuthorQueryService publicAuthorQueryService;

    private FeedItemAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new FeedItemAssembler(counterService, commentService, publicAuthorQueryService);
    }

    @Test
    void mineFeedRowMappingAddsCommentCountsInOneBatch() {
        PostFeedRow row = row(101L);
        when(counterService.getCounts("post", "101", List.of("like", "fav"))).thenReturn(Map.of());
        when(commentService.countActiveByPostIds(List.of(101L))).thenReturn(Map.of(101L, 4L));
        when(publicAuthorQueryService.findByIds(List.of(7L))).thenReturn(Map.of());

        List<FeedItemResponse> items = assembler.fromRows(List.of(row), null, true);

        assertThat(items.getFirst().commentCount()).isEqualTo(4L);
        verify(commentService).countActiveByPostIds(List.of(101L));
    }

    @Test
    void followingFeedBatchMappingAddsCommentCountsInOneBatch() {
        PostFeedRow row = row(202L);
        when(counterService.getCountsBatch("post", List.of("202"), List.of("like", "fav")))
                .thenReturn(Map.of("202", Map.of()));
        when(counterService.batchIsLiked(9L, List.of(202L))).thenReturn(Map.of(202L, false));
        when(counterService.batchIsFaved(9L, List.of(202L))).thenReturn(Map.of(202L, false));
        when(commentService.countActiveByPostIds(List.of(202L))).thenReturn(Map.of(202L, 3L));
        when(publicAuthorQueryService.findByIds(List.of(7L))).thenReturn(Map.of());

        List<FeedItemResponse> items = assembler.fromRowsBatch(List.of(row), 9L);

        assertThat(items.getFirst().commentCount()).isEqualTo(3L);
        verify(commentService).countActiveByPostIds(List.of(202L));
    }

    private static PostFeedRow row(long id) {
        PostFeedRow row = new PostFeedRow();
        row.setId(id);
        row.setSlug("post-" + id);
        row.setTitle("title");
        row.setTags("[]");
        row.setImgUrls("[]");
        row.setAuthorId(7L);
        row.setAuthorNickname("Author");
        return row;
    }
}
