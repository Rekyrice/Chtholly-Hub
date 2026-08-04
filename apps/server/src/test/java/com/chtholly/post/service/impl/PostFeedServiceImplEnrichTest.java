package com.chtholly.post.service.impl;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostFeedServiceImplEnrichTest {

    private final PublicPostFeedQueryService publicFeed = mock(PublicPostFeedQueryService.class);
    private final PersonalPostFeedService personalFeed = mock(PersonalPostFeedService.class);
    private final PostFeedServiceImpl service = new PostFeedServiceImpl(publicFeed, personalFeed);

    @Test
    void publicFeedIsDelegatedToDedicatedQueryService() {
        @SuppressWarnings("unchecked")
        PageResponse<FeedItemResponse> expected = mock(PageResponse.class);
        when(publicFeed.getPublicFeed(1, null, 10, 7L, "动画", 9L)).thenReturn(expected);

        assertThat(service.getPublicFeed(1, null, 10, 7L, "动画", 9L)).isSameAs(expected);
        verify(publicFeed).getPublicFeed(1, null, 10, 7L, "动画", 9L);
    }

    @Test
    void followingFeedIsDelegatedToUserScopedService() {
        @SuppressWarnings("unchecked")
        PageResponse<FeedItemResponse> expected = mock(PageResponse.class);
        when(personalFeed.getFollowingFeed(9L, 2, 8)).thenReturn(expected);

        assertThat(service.getFollowingFeed(9L, 2, 8)).isSameAs(expected);
        verify(personalFeed).getFollowingFeed(9L, 2, 8);
    }
}
