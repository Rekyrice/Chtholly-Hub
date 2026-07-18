package com.chtholly.post.service.impl;

import com.chtholly.cache.config.CacheProperties;
import com.chtholly.cache.hotkey.HotKeyDetector;
import com.chtholly.cache.observability.CacheMetrics;
import com.chtholly.comment.service.CommentService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.post.util.FeedCursor;
import com.chtholly.user.service.PublicAuthorQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostFeedServiceImplCacheReadModeTest {

    @Mock private PostMapper mapper;
    @Mock private StringRedisTemplate redis;
    @Mock private CounterService counterService;
    @Mock private CommentService commentService;
    @Mock private HotKeyDetector hotKey;
    @Mock private PersonalPostFeedService personalFeedService;
    @Mock private PublicAuthorQueryService publicAuthorQueryService;
    @Mock private CacheMetrics cacheMetrics;

    @Test
    void dbOnlyOffsetBypassesStaleSharedCaches() {
        Cache<String, PageResponse<FeedItemResponse>> local = Caffeine.newBuilder().build();
        PostFeedServiceImpl service = newService(CacheProperties.ReadMode.DB_ONLY, local);
        local.put(service.publicFeedPageKey(1, null, 10, null, null), page(item("stale")));
        when(mapper.listFeedPublic(11, 0)).thenReturn(List.of(row(42L)));
        stubEnrichment(42L);

        PageResponse<FeedItemResponse> response = service.getPublicFeed(1, null, 10, null, null, null);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("42");
        assertThat(local.getIfPresent(service.publicFeedPageKey(1, null, 10, null, null))
                .items()).extracting(FeedItemResponse::id).containsExactly("stale");
        verifyNoInteractions(redis, hotKey);
        verify(cacheMetrics).recordMysqlQuery();
        verify(cacheMetrics).recordSameKeyLoad();
    }

    @Test
    void dbOnlyCursorBypassesStaleSharedCaches() {
        Cache<String, PageResponse<FeedItemResponse>> local = Caffeine.newBuilder().build();
        PostFeedServiceImpl service = newService(CacheProperties.ReadMode.DB_ONLY, local);
        Instant cursorTime = Instant.parse("2026-07-01T00:00:00Z");
        String cursor = FeedCursor.encode(cursorTime, 99L);
        local.put(service.publicFeedPageKey(null, cursor, 10, null, null), page(item("stale")));
        when(mapper.listFeedPublicByCursor(cursorTime, 99L, 11)).thenReturn(List.of(row(43L)));
        stubEnrichment(43L);

        PageResponse<FeedItemResponse> response = service.getPublicFeed(null, cursor, 10, null, null, null);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("43");
        verifyNoInteractions(redis, hotKey);
        verify(cacheMetrics).recordMysqlQuery();
        verify(cacheMetrics).recordSameKeyLoad();
    }

    @Test
    void fullNoSingleFlightAllowsConcurrentOffsetLoads() throws Exception {
        Cache<String, PageResponse<FeedItemResponse>> local = Caffeine.newBuilder().build();
        stubEmptyRedisFragments();
        CountDownLatch loadsEntered = new CountDownLatch(2);
        CountDownLatch releaseLoads = new CountDownLatch(1);
        when(mapper.listFeedPublic(11, 0)).thenAnswer(ignored -> {
            loadsEntered.countDown();
            if (!releaseLoads.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent feed loads did not arrive");
            }
            return List.of(row(42L));
        });
        stubEnrichment(42L);
        PostFeedServiceImpl service = newService(CacheProperties.ReadMode.FULL_NO_SINGLEFLIGHT, local);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PageResponse<FeedItemResponse>> first = executor.submit(
                    () -> service.getPublicFeed(1, null, 10, null, null, null));
            Future<PageResponse<FeedItemResponse>> second = executor.submit(
                    () -> service.getPublicFeed(1, null, 10, null, null, null));
            assertThat(loadsEntered.await(3, TimeUnit.SECONDS)).isTrue();
            releaseLoads.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).items()).hasSize(1);
            assertThat(second.get(5, TimeUnit.SECONDS).items()).hasSize(1);
            verify(mapper, times(2)).listFeedPublic(11, 0);
            verify(cacheMetrics, times(2)).recordSameKeyLoad();
        } finally {
            releaseLoads.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void fullModeCoalescesConcurrentOffsetLoads() throws Exception {
        Cache<String, PageResponse<FeedItemResponse>> local = Caffeine.newBuilder().build();
        CountDownLatch secondInitialRead = new CountDownLatch(1);
        stubBackedRedisFragments(secondInitialRead);
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(mapper.listFeedPublic(11, 0)).thenAnswer(ignored -> {
            loadEntered.countDown();
            if (!releaseLoad.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("feed origin load was not released");
            }
            return List.of(row(42L));
        });
        stubEnrichment(42L);
        PostFeedServiceImpl service = newService(CacheProperties.ReadMode.FULL, local);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PageResponse<FeedItemResponse>> first = executor.submit(
                    () -> service.getPublicFeed(1, null, 10, null, null, null));
            assertThat(loadEntered.await(3, TimeUnit.SECONDS)).isTrue();
            Future<PageResponse<FeedItemResponse>> second = executor.submit(
                    () -> service.getPublicFeed(1, null, 10, null, null, null));
            assertThat(secondInitialRead.await(3, TimeUnit.SECONDS)).isTrue();
            releaseLoad.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).items()).hasSize(1);
            assertThat(second.get(5, TimeUnit.SECONDS).items()).hasSize(1);
            verify(mapper).listFeedPublic(11, 0);
            verify(cacheMetrics).recordSameKeyLoad();
        } finally {
            releaseLoad.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cacheReloadReplacesTheExistingIdListBeforePush() {
        Cache<String, PageResponse<FeedItemResponse>> local = Caffeine.newBuilder().build();
        ListOperations<String, String> lists = stubEmptyRedisFragments();
        when(mapper.listFeedPublic(11, 0)).thenReturn(List.of(row(42L)));
        stubEnrichment(42L);
        PostFeedServiceImpl service = newService(CacheProperties.ReadMode.FULL_NO_SINGLEFLIGHT, local);
        long hour = System.currentTimeMillis() / 3_600_000L;
        String idsKey = "feed:public:ids:10:" + hour + ":1";

        service.getPublicFeed(1, null, 10, null, null, null);

        InOrder order = inOrder(redis, lists);
        order.verify(redis).delete(List.of(idsKey, idsKey + ":hasMore", idsKey + ":nextCursor"));
        order.verify(lists).leftPushAll(eq(idsKey), anyCollection());
    }

    private PostFeedServiceImpl newService(
            CacheProperties.ReadMode readMode,
            Cache<String, PageResponse<FeedItemResponse>> local
    ) {
        CacheProperties properties = new CacheProperties();
        properties.setReadMode(readMode);
        return new PostFeedServiceImpl(
                mapper, redis, new ObjectMapper().findAndRegisterModules(), counterService, commentService, local, hotKey,
                personalFeedService, publicAuthorQueryService, properties, cacheMetrics);
    }

    private void stubEnrichment(long postId) {
        when(counterService.getCounts("post", String.valueOf(postId), List.of("like", "fav"))).thenReturn(Map.of());
        lenient().when(counterService.getCountsBatch("post", List.of(String.valueOf(postId)), List.of("like", "fav")))
                .thenReturn(Map.of());
        when(commentService.countActiveByPostIds(List.of(postId))).thenReturn(Map.of());
        when(publicAuthorQueryService.findByIds(List.of(7L))).thenReturn(Map.of());
    }

    @SuppressWarnings("unchecked")
    private ListOperations<String, String> stubEmptyRedisFragments() {
        ListOperations<String, String> lists = mock(ListOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        ZSetOperations<String, String> sortedSets = mock(ZSetOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(sortedSets);
        when(redis.type(anyString())).thenReturn(DataType.NONE);
        when(lists.range(anyString(), eq(0L), anyLong())).thenReturn(null);
        when(values.get(anyString())).thenReturn(null);
        return lists;
    }

    @SuppressWarnings("unchecked")
    private void stubBackedRedisFragments(CountDownLatch secondInitialRead) {
        ListOperations<String, String> lists = mock(ListOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        ZSetOperations<String, String> sortedSets = mock(ZSetOperations.class);
        AtomicReference<List<String>> cachedIds = new AtomicReference<>();
        Map<String, String> fragments = new ConcurrentHashMap<>();
        AtomicInteger listReads = new AtomicInteger();
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(sortedSets);
        when(redis.type(anyString())).thenReturn(DataType.NONE);
        when(lists.range(anyString(), eq(0L), anyLong())).thenAnswer(ignored -> {
            if (listReads.incrementAndGet() == 3) {
                secondInitialRead.countDown();
            }
            return cachedIds.get();
        });
        doAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(1);
            cachedIds.set(new ArrayList<>(ids));
            return (long) ids.size();
        }).when(lists).leftPushAll(anyString(), anyCollection());
        when(values.get(anyString())).thenAnswer(invocation -> fragments.get(invocation.getArgument(0)));
        when(values.multiGet(anyCollection())).thenAnswer(invocation -> {
            Collection<String> keys = invocation.getArgument(0);
            return keys.stream().map(fragments::get).toList();
        });
        doAnswer(invocation -> {
            fragments.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    private PageResponse<FeedItemResponse> page(FeedItemResponse item) {
        return PageResponse.offset(List.of(item), 1, 10, 0L, false, null);
    }

    private FeedItemResponse item(String id) {
        return new FeedItemResponse(
                id, "slug-" + id, "stale", null, null, List.of(), "7", "owner", null, "Owner", "[]",
                0L, 0L, null, null, null, null);
    }

    private PostFeedRow row(long id) {
        PostFeedRow row = new PostFeedRow();
        row.setId(id);
        row.setSlug("post-" + id);
        row.setTitle("Post " + id);
        row.setDescription("Description");
        row.setTags("[]");
        row.setImgUrls("[]");
        row.setAuthorId(7L);
        row.setAuthorHandle("owner");
        row.setAuthorNickname("Owner");
        row.setAuthorTagJson("[]");
        row.setPublishTime(Instant.parse("2026-07-02T00:00:00Z"));
        return row;
    }
}
