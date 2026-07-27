package com.chtholly.post.listener;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.common.job.CleanupProperties;
import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.service.UserCounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.mapper.PostMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedCacheInvalidationListenerTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private UserCounterService userCounterService;
    @Mock
    private PostMapper postMapper;

    private Cache<String, PageResponse<FeedItemResponse>> feedCache;
    private FeedCacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        feedCache = Caffeine.newBuilder().build();
        when(redis.opsForSet()).thenReturn(setOperations);
        listener = new FeedCacheInvalidationListener(
                feedCache,
                redis,
                userCounterService,
                postMapper,
                new CleanupProperties(null, null, null, null, null,
                        new CleanupProperties.FeedPages(100)));
    }

    @Test
    void projectedLikeInvalidatesFeedPagesAndAuthorCounterCache() {
        String pageKey = "feed:public:page:1";
        FeedItemResponse item = new FeedItemResponse(
                "42", "slug", "title", "description", null, List.of(),
                null, null, null, 4L, 2L, true, true, false);
        feedCache.put(pageKey, PageResponse.offset(List.of(item), 1, 10, 1L));
        long hourSlot = System.currentTimeMillis() / 3_600_000L;
        when(setOperations.members("feed:public:index:42:" + hourSlot))
                .thenReturn(Set.of(pageKey));
        when(setOperations.members("feed:public:index:42:" + (hourSlot - 1)))
                .thenReturn(Set.of());
        CounterEvent event = CounterEvent.of("201", "post", "42", "like", 1, 9L, 1);
        event.setPostCreatorId(10L);

        listener.onCounterChanged(event);

        assertThat(feedCache.getIfPresent(pageKey)).isNull();
        verify(redis).delete(pageKey);
        verify(setOperations).remove("feed:public:index:42:" + hourSlot, pageKey);
        verify(setOperations).remove("feed:public:index:42:" + (hourSlot - 1), pageKey);
        verify(userCounterService).invalidateReactionCounters(10L);
        verify(postMapper, never()).findById(42L);
    }
}
