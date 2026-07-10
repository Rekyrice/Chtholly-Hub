package com.chtholly.agent.proactive;

import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.service.PostService;
import com.chtholly.search.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentProactiveServiceTest {
    @Test
    void dailyDigestUsesContentDependenciesWithoutOtherDomains() {
        PostService posts = mock(PostService.class);
        SearchService search = mock(SearchService.class);
        CounterService counters = mock(CounterService.class);
        ProactiveNotificationDispatcher dispatcher = mock(ProactiveNotificationDispatcher.class);
        SeedCurationReader curation = mock(SeedCurationReader.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ProactiveTriggerEngine.UserActivityProvider activity = mock(ProactiveTriggerEngine.UserActivityProvider.class);
        CharacterStateService state = mock(CharacterStateService.class);
        ProactiveAudienceService audience = new ProactiveAudienceService(
                state, (CharacterStateUserActivityProvider) null);
        when(state.findUserIdsActiveSince(any())).thenReturn(List.of(7L));
        when(search.recommendHot(Set.of(), 3, null)).thenReturn(List.of(feedItem()));

        new ContentProactiveService(posts, search, counters, dispatcher, curation, redis,
                activity, null, audience, prompt -> "").sendDailyHotDigest();

        verify(dispatcher).send(eq(7L), any(Notification.class), any());
    }

    private static FeedItemResponse feedItem() {
        return new FeedItemResponse("1", "slug", "Title", "desc", null, List.of(),
                null, "author", null, 1L, 0L, false, false, false);
    }
}
