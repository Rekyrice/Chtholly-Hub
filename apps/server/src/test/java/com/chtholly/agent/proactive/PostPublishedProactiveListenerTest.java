package com.chtholly.agent.proactive;

import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.event.PostPublishedEvent;
import com.chtholly.post.service.PostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostPublishedProactiveListenerTest {

    @Mock
    private PostService postService;
    @Mock
    private CharacterStateUserActivityProvider activityProvider;
    @Mock
    private ProactiveNotificationDispatcher dispatcher;

    @InjectMocks
    private PostPublishedProactiveListener listener;

    @Test
    void given_publicPost_when_published_then_notifiesInterestedUsers() {
        when(postService.getDetail(100L, null)).thenReturn(new PostDetailResponse(
                "100",
                "slug",
                "新故事标题",
                "desc",
                "content.md",
                List.of(),
                List.of("治愈"),
                "2",
                null,
                "author",
                "[]",
                0L,
                0L,
                false,
                false,
                false,
                "public",
                "article",
                Instant.now()));
        when(activityProvider.findUsersInterestedInTags(List.of("治愈"), 2L)).thenReturn(List.of(9L));
        when(dispatcher.send(eq(9L), any(Notification.class), any())).thenReturn(true);

        listener.onPostPublished(new PostPublishedEvent(100L, 2L, Instant.now(), "public"));

        verify(dispatcher).send(
                eq(9L),
                any(Notification.class),
                eq(ProactiveNotificationDispatcher.Category.RECOMMEND));
    }
}
