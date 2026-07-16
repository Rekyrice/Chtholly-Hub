package com.chtholly.post.service.impl;

import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class PostFeedServiceImplEnrichTest {

    @Mock
    private CounterService counterService;
    @Mock
    private PersonalPostFeedService personalFeedService;
    @Mock
    private PublicAuthorQueryService publicAuthorQueryService;

    private Method enrichMethod;

    @BeforeEach
    void setUp() throws Exception {
        enrichMethod = PostFeedServiceImpl.class.getDeclaredMethod("enrich", List.class, Long.class);
        enrichMethod.setAccessible(true);
    }

    @Test
    void given_twentyPosts_when_enrich_then_batchPipelineTwice() throws Exception {
        PostFeedServiceImpl service = newMinimalService();
        List<FeedItemResponse> base = List.of(
                item("1", "7"), item("2", "8"), item("3", "7")
        );
        when(counterService.batchIsLiked(eq(9L), eq(List.of(1L, 2L, 3L))))
                .thenReturn(Map.of(1L, true, 2L, false, 3L, false));
        when(counterService.batchIsFaved(eq(9L), eq(List.of(1L, 2L, 3L))))
                .thenReturn(Map.of(1L, false, 2L, true, 3L, false));
        when(publicAuthorQueryService.findByIds(List.of(7L, 8L))).thenReturn(Map.of(
                7L, author(7L, "rekyrice", "Rekyrice"),
                8L, author(8L, "quiet-user", "Quiet User")
        ));

        @SuppressWarnings("unchecked")
        List<FeedItemResponse> out = (List<FeedItemResponse>) enrichMethod.invoke(service, base, 9L);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).liked()).isTrue();
        assertThat(out.get(1).faved()).isTrue();
        assertThat(out.get(0).authorHandle()).isEqualTo("rekyrice");
        assertThat(out.get(2).authorNickname()).isEqualTo("Rekyrice");
        verify(counterService, times(1)).batchIsLiked(9L, List.of(1L, 2L, 3L));
        verify(counterService, times(1)).batchIsFaved(9L, List.of(1L, 2L, 3L));
        verify(publicAuthorQueryService, times(1)).findByIds(List.of(7L, 8L));
    }

    @Test
    void followingFeedIsDelegatedToUserScopedService() {
        PageResponse<FeedItemResponse> expected = mock(PageResponse.class);
        when(personalFeedService.getFollowingFeed(9L, 2, 8)).thenReturn(expected);
        PostFeedServiceImpl service = newService(personalFeedService);

        assertThat(service.getFollowingFeed(9L, 2, 8)).isSameAs(expected);
        verify(personalFeedService).getFollowingFeed(9L, 2, 8);
    }

    private PostFeedServiceImpl newMinimalService() {
        return newService(null);
    }

    private PostFeedServiceImpl newService(PersonalPostFeedService personalFeed) {
        return new PostFeedServiceImpl(
                null,
                null,
                null,
                counterService,
                null,
                null,
                personalFeed,
                publicAuthorQueryService
        );
    }

    private static FeedItemResponse item(String id, String authorId) {
        return new FeedItemResponse(
                id, "slug-" + id, "title", "desc", null,
                List.of(), authorId, "old-handle", null, "nick", null,
                0L, 0L, null, null, null, null);
    }

    private static PublicAuthorSnapshot author(long id, String handle, String nickname) {
        return new PublicAuthorSnapshot(id, handle, nickname, "/avatar.webp", "简介", "[]", null);
    }
}
