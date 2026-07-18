package com.chtholly.search.service.impl;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.chtholly.counter.service.CounterService;
import com.chtholly.comment.service.CommentService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchHitMapperTest {

    @Mock private CounterService counterService;
    @Mock private CommentService commentService;
    @Mock private PublicAuthorQueryService publicAuthorQueryService;

    @Test
    void mapPostHitsOverlaysAuthorsWithOneMysqlBatchQuery() {
        Hit<Map<String, Object>> first = hit(Map.of(
                "content_id", 1L,
                "author_id", 7L,
                "author_handle", "old-handle",
                "author_nickname", "旧昵称"
        ));
        Hit<Map<String, Object>> second = hit(Map.of(
                "content_id", 2L,
                "author_id", 8L,
                "author_nickname", "另一个旧昵称"
        ));
        when(publicAuthorQueryService.findByIds(List.of(7L, 8L))).thenReturn(Map.of(
                7L, author(7L, "rekyrice", "Rekyrice"),
                8L, author(8L, "quiet-user", "Quiet User")
        ));
        when(commentService.countActiveByPostIds(List.of(1L, 2L))).thenReturn(Map.of(1L, 3L, 2L, 1L));
        SearchHitMapper mapper = new SearchHitMapper(counterService, publicAuthorQueryService, commentService);

        List<FeedItemResponse> items = mapper.mapPostHits(List.of(first, second), null);

        assertThat(items).extracting(FeedItemResponse::authorHandle)
                .containsExactly("rekyrice", "quiet-user");
        assertThat(items).extracting(FeedItemResponse::authorNickname)
                .containsExactly("Rekyrice", "Quiet User");
        assertThat(items).extracting(FeedItemResponse::commentCount)
                .containsExactly(3L, 1L);
        verify(publicAuthorQueryService).findByIds(List.of(7L, 8L));
        verify(commentService).countActiveByPostIds(List.of(1L, 2L));
    }

    @SuppressWarnings("unchecked")
    private Hit<Map<String, Object>> hit(Map<String, Object> source) {
        Hit<Map<String, Object>> hit = mock(Hit.class);
        when(hit.source()).thenReturn(source);
        when(hit.highlight()).thenReturn(null);
        return hit;
    }

    private PublicAuthorSnapshot author(long id, String handle, String nickname) {
        return new PublicAuthorSnapshot(id, handle, nickname, "/avatar.webp", "简介", "[]", null);
    }
}
