package com.chtholly.post.listener;

import com.chtholly.post.event.PostPublishedEvent;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.relation.event.FollowCanceledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FeedTimelineListenerTest {

    @Mock
    private FeedTimelineService feedTimelineService;

    private FeedTimelineListener listener;

    @BeforeEach
    void setUp() {
        listener = new FeedTimelineListener(feedTimelineService);
    }

    @Test
    void given_publicPost_when_onPostPublished_then_pushesTimeline() {
        PostPublishedEvent event = new PostPublishedEvent(
                1L, 10L, Instant.now(), "public");

        listener.onPostPublished(event);

        verify(feedTimelineService).onPostPublished(10L, 1L, event.publishTime());
    }

    @Test
    void given_privatePost_when_onPostPublished_then_skips() {
        PostPublishedEvent event = new PostPublishedEvent(
                1L, 10L, Instant.now(), "private");

        listener.onPostPublished(event);

        verify(feedTimelineService, never()).onPostPublished(anyLong(), anyLong(), any());
    }

    @Test
    void given_pushFails_when_onPostPublished_then_doesNotPropagate() {
        PostPublishedEvent event = new PostPublishedEvent(
                1L, 10L, Instant.now(), "public");
        doThrow(new RuntimeException("redis down"))
                .when(feedTimelineService).onPostPublished(10L, 1L, event.publishTime());

        assertThatCode(() -> listener.onPostPublished(event)).doesNotThrowAnyException();
    }

    @Test
    void given_unfollow_when_onFollowCanceled_then_cleansTimeline() {
        FollowCanceledEvent event = new FollowCanceledEvent(100L, 20L);

        listener.onFollowCanceled(event);

        verify(feedTimelineService).removeAuthorFromTimeline(100L, 20L);
    }
}
