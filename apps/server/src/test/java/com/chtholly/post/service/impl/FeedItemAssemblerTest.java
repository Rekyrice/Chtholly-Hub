package com.chtholly.post.service.impl;

import com.chtholly.comment.service.CommentService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedItemAssemblerTest {

    @Mock private CounterService counterService;
    @Mock private CommentService commentService;
    @Mock private PublicAuthorQueryService publicAuthorQueryService;

    private FeedItemAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new FeedItemAssembler(counterService, commentService, publicAuthorQueryService);
    }

    @Test
    void enrichUsesBatchFlagsCommentsAndAuthors() {
        List<FeedItemResponse> base = List.of(
                item("1", "7"), item("2", "8"), item("3", "7"));
        when(counterService.batchIsLiked(9L, List.of(1L, 2L, 3L)))
                .thenReturn(Map.of(1L, true, 2L, false, 3L, false));
        when(counterService.batchIsFaved(9L, List.of(1L, 2L, 3L)))
                .thenReturn(Map.of(1L, false, 2L, true, 3L, false));
        when(publicAuthorQueryService.findByIds(List.of(7L, 8L))).thenReturn(Map.of(
                7L, author(7L, "rekyrice", "Rekyrice"),
                8L, author(8L, "quiet-user", "Quiet User")));
        when(commentService.countActiveByPostIds(List.of(1L, 2L, 3L)))
                .thenReturn(Map.of(1L, 4L, 2L, 2L, 3L, 0L));

        List<FeedItemResponse> out = assembler.enrich(base, 9L);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).liked()).isTrue();
        assertThat(out.get(1).faved()).isTrue();
        assertThat(out.get(0).authorHandle()).isEqualTo("rekyrice");
        assertThat(out.get(2).authorNickname()).isEqualTo("Rekyrice");
        assertThat(out).extracting(FeedItemResponse::commentCount)
                .containsExactly(4L, 2L, 0L);
        verify(counterService, times(1)).batchIsLiked(9L, List.of(1L, 2L, 3L));
        verify(counterService, times(1)).batchIsFaved(9L, List.of(1L, 2L, 3L));
        verify(publicAuthorQueryService, times(1)).findByIds(List.of(7L, 8L));
        verify(commentService, times(1)).countActiveByPostIds(List.of(1L, 2L, 3L));
    }

    @Test
    void fromRowsKeepsTopAndAddsCommentCounts() {
        PostFeedRow row = row(11L);
        row.setIsTop(true);
        when(counterService.getCounts("post", "11", List.of("like", "fav"))).thenReturn(Map.of());
        when(commentService.countActiveByPostIds(List.of(11L))).thenReturn(Map.of(11L, 5L));
        when(publicAuthorQueryService.findByIds(List.of(7L))).thenReturn(Map.of());

        FeedItemResponse result = assembler.fromRows(List.of(row), null, true).getFirst();

        assertThat(result.commentCount()).isEqualTo(5L);
        assertThat(result.isTop()).isTrue();
    }

    private static FeedItemResponse item(String id, String authorId) {
        return new FeedItemResponse(
                id, "slug-" + id, "Title", null, null, List.of(), authorId, null, null,
                null, "[]", 0L, 0L, null, null, null, null);
    }

    private static PublicAuthorSnapshot author(long id, String handle, String nickname) {
        return new PublicAuthorSnapshot(
                id, handle, nickname, "/avatar.webp", "bio", "[]", Instant.parse("2026-07-01T00:00:00Z"));
    }

    private static PostFeedRow row(long id) {
        PostFeedRow row = new PostFeedRow();
        row.setId(id);
        row.setSlug("post-" + id);
        row.setTitle("Post " + id);
        row.setTags("[]");
        row.setImgUrls("[]");
        row.setAuthorId(7L);
        row.setAuthorNickname("Author");
        return row;
    }
}
