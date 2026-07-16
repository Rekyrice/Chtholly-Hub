package com.chtholly.post.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.chtholly.counter.service.UserCounterService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.service.PostFeedService;
import com.chtholly.llm.rag.PostRagIndexer;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.tag.service.TagService;
import com.chtholly.search.index.SearchIndexService;
import com.chtholly.post.model.Post;
import com.chtholly.storage.config.OssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

    @Mock
    private PostMapper mapper;
    @Mock
    private UserCounterService userCounterService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private PostRagIndexer ragIndexService;
    @Mock
    private OutboxMapper outboxMapper;
    @Mock
    private TagService tagService;
    @Mock
    private SearchIndexService searchIndexService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private PostFeedService postFeedService;
    @Mock
    private PostDetailQueryService detailQueryService;
    @Mock
    private PostBackgroundQueryService backgroundQueryService;

    private Cache<String, PageResponse<FeedItemResponse>> feedPublicCache;
    private Cache<String, PostDetailResponse> postDetailCache;
    private PostServiceImpl service;

    @BeforeEach
    void setUp() {
        feedPublicCache = Caffeine.newBuilder().build();
        postDetailCache = Caffeine.newBuilder().build();

        lenient().when(redis.opsForSet()).thenReturn(setOperations);

        OssProperties ossProperties = new OssProperties();
        PostCacheInvalidator cacheInvalidator =
                new PostCacheInvalidator(redis, feedPublicCache, postDetailCache);

        // 构造参数顺序需与 PostServiceImpl 一致，末尾为 PostFeedService
        service = new PostServiceImpl(
                mapper,
                new SnowflakeIdGenerator(),
                new ObjectMapper(),
                ossProperties,
                userCounterService,
                cacheInvalidator,
                ragIndexService,
                outboxMapper,
                tagService,
                searchIndexService,
                eventPublisher,
                postFeedService,
                detailQueryService,
                backgroundQueryService);
    }

    private Post draftOwnedBy(long postId, long creatorId) {
        return Post.builder()
                .id(postId)
                .creatorId(creatorId)
                .status("draft")
                .tags("[]")
                .build();
    }

    @Test
    void detailReadsAreDelegatedToDedicatedQueryService() {
        PostDetailResponse detail = org.mockito.Mockito.mock(PostDetailResponse.class);
        when(detailQueryService.getDetail(42L, 9L)).thenReturn(detail);

        assertThat(service.getDetail(42L, 9L)).isSameAs(detail);
        verify(detailQueryService).getDetail(42L, 9L);
    }

    @Test
    void backgroundQueriesAreDelegatedWithoutEnteringWritePath() {
        when(backgroundQueryService.getRecentPosts(java.time.Duration.ofHours(6)))
                .thenReturn(List.of());

        assertThat(service.getRecentPosts(java.time.Duration.ofHours(6))).isEmpty();
        verify(backgroundQueryService).getRecentPosts(java.time.Duration.ofHours(6));
    }

    @Test
    void publishPropagatesOutboxFailure() {
        long creatorId = 9L;
        long postId = 42L;
        when(mapper.publish(postId, creatorId)).thenReturn(1);
        when(mapper.findById(postId)).thenReturn(null);
        doThrow(new IllegalStateException("outbox down"))
                .when(outboxMapper)
                .insert(anyLong(), eq("post"), eq(postId), eq("PostPublished"), anyString());

        assertThatThrownBy(() -> service.publish(creatorId, postId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox down");
    }

    @Test
    void updateTopInvalidatesMyPublishedCache() {
        long creatorId = 9L;
        long postId = 42L;
        when(mapper.updateTop(postId, creatorId, true)).thenReturn(1);

        service.updateTop(creatorId, postId, true);

        verify(postFeedService).invalidateMyPublishedCache(creatorId);
    }

    @Test
    void updateMetadataInvalidatesLocalPublicFeedForCurrentAndPreviousHour() {
        long postId = 123L;
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String currentIndexKey = "feed:public:index:" + postId + ":" + hourSlot;
        String previousIndexKey = "feed:public:index:" + postId + ":" + (hourSlot - 1);
        String currentPageKey = "feed:public:20:1:v1";
        String previousPageKey = "feed:public:20:2:v1";

        feedPublicCache.put(currentPageKey, PageResponse.offset(List.of(), 1, 20, 0L));
        feedPublicCache.put(previousPageKey, PageResponse.offset(List.of(), 2, 20, 0L));

        when(mapper.findById(postId)).thenReturn(draftOwnedBy(postId, 1L));
        when(mapper.updateMetadata(any())).thenReturn(1);
        when(setOperations.members(currentIndexKey)).thenReturn(Set.of(currentPageKey, ""));
        when(setOperations.members(previousIndexKey)).thenReturn(Set.of(previousPageKey));

        service.updateMetadata(1L, postId, "title", 2L, List.of("tag"), List.of("img"), "public", false, "desc");

        assertThat(feedPublicCache.getIfPresent(currentPageKey)).isNull();
        assertThat(feedPublicCache.getIfPresent(previousPageKey)).isNull();
        verify(setOperations, never()).remove(eq(currentIndexKey), eq(""));
        verify(redis, times(2)).delete("post:detail:" + postId + ":v3");
    }

    @Test
    void updateMetadataRemovesStaleReverseIndexWhenLocalPageMissing() {
        long postId = 456L;
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String currentIndexKey = "feed:public:index:" + postId + ":" + hourSlot;
        String stalePageKey = "feed:public:10:9:v1";

        when(mapper.findById(postId)).thenReturn(draftOwnedBy(postId, 1L));
        when(mapper.updateMetadata(any())).thenReturn(1);
        when(setOperations.members(currentIndexKey)).thenReturn(Set.of(stalePageKey));
        when(setOperations.members("feed:public:index:" + postId + ":" + (hourSlot - 1))).thenReturn(Set.of());

        service.updateMetadata(1L, postId, "title", null, List.of(), List.of(), "public", false, "desc");

        verify(setOperations, times(2)).remove(currentIndexKey, stalePageKey);
    }

    @Test
    void deleteAlsoInvalidatesLocalPublicFeedBecauseItUsesDoubleDelete() {
        long postId = 789L;
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String indexKey = "feed:public:index:" + postId + ":" + hourSlot;
        String pageKey = "feed:public:20:3:v1";
        feedPublicCache.put(pageKey, PageResponse.offset(List.of(), 3, 20, 0L));

        when(mapper.findById(postId)).thenReturn(draftOwnedBy(postId, 1L));
        when(setOperations.members(indexKey)).thenReturn(Set.of(pageKey));
        when(setOperations.members("feed:public:index:" + postId + ":" + (hourSlot - 1))).thenReturn(Set.of());
        when(mapper.softDelete(postId, 1L)).thenReturn(1);

        service.delete(1L, postId);

        assertThat(feedPublicCache.getIfPresent(pageKey)).isNull();
        verify(setOperations, times(2)).members(indexKey);
    }

    @Test
    void confirmContentAlsoInvalidatesLocalPublicFeedBecauseItUsesDoubleDelete() {
        long postId = 9527L;
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String indexKey = "feed:public:index:" + postId + ":" + hourSlot;
        String pageKey = "feed:public:20:4:v1";
        feedPublicCache.put(pageKey, PageResponse.offset(List.of(), 4, 20, 0L));

        when(setOperations.members(indexKey)).thenReturn(Set.of(pageKey));
        when(setOperations.members("feed:public:index:" + postId + ":" + (hourSlot - 1))).thenReturn(Set.of());
        when(mapper.updateContent(any())).thenReturn(1);

        service.confirmContent(1L, postId, "object-key", "etag", 100L, "sha256");

        assertThat(feedPublicCache.getIfPresent(pageKey)).isNull();
        verify(setOperations, times(2)).members(indexKey);
    }

    // ==================== 新增双删测试（带详细日志）====================

    @Test
    void invalidateFeedLocalCache_shouldAlwaysPerformDoubleDelete_whenLocalCacheExists() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🧪 [TEST-1] 本地缓存存在时执行双删");
        System.out.println("=".repeat(80));

        long postId = 1001L;
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String indexKey = "feed:public:index:" + postId + ":" + hourSlot;
        String pageKey = "feed:public:20:5:v1";

        System.out.println("\n📝 [准备阶段]");
        System.out.println("  ├─ Post ID: " + postId);
        System.out.println("  ├─ 当前时间槽: " + hourSlot);
        System.out.println("  ├─ Redis 索引 Key: " + indexKey);
        System.out.println("  └─ 页面缓存 Key: " + pageKey);

        feedPublicCache.put(pageKey, PageResponse.offset(List.of(), 5, 20, 0L));

        System.out.println("\n📦 [初始状态] 模拟本地缓存已存在数据:");
        System.out.println("  └─ feedPublicCache.getIfPresent('" + pageKey + "') = "
                + (feedPublicCache.getIfPresent(pageKey) != null ? "✅ 存在" : "❌ 不存在"));

        when(mapper.findById(postId)).thenReturn(draftOwnedBy(postId, 1L));
        when(mapper.updateMetadata(any())).thenReturn(1);
        when(setOperations.members(indexKey)).thenReturn(Set.of(pageKey));
        when(setOperations.members("feed:public:index:" + postId + ":" + (hourSlot - 1))).thenReturn(Set.of());

        System.out.println("\n⚙️  [执行操作] 调用 service.updateMetadata()...");
        service.updateMetadata(1L, postId, "title", null, List.of(), List.of(), "public", false, "desc");

        System.out.println("\n✅ [验证结果] 双删执行后:");

        boolean localCacheCleared = feedPublicCache.getIfPresent(pageKey) == null;
        System.out.println("  ├─ ✅ 本地缓存清除: " + (localCacheCleared ? "成功 ✓" : "失败 ✗"));
        System.out.println("  └─ ✅ Redis 索引清理: 验证中...");

        assertThat(feedPublicCache.getIfPresent(pageKey)).isNull();
        verify(setOperations, times(2)).remove(indexKey, pageKey);

        System.out.println("\n  └─ ✅ Redis remove() 调用次数: 2 次 (延迟双删)");
        System.out.println("\n🎉 [结论] 双删策略执行成功！本地缓存和Redis索引都已清理\n");
    }

    @Test
    void invalidateFeedLocalCache_shouldCleanRedisIndexEvenWhenLocalCacheMissing() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🧪 [TEST-2] 本地缓存不存在时也清理Redis索引");
        System.out.println("=".repeat(80));

        long postId = 1002L;
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String indexKey = "feed:public:index:" + postId + ":" + hourSlot;
        String stalePageKey = "feed:public:10:99:v1";

        System.out.println("\n📝 [准备阶段]");
        System.out.println("  ├─ Post ID: " + postId);
        System.out.println("  ├─ Redis 索引 Key: " + indexKey);
        System.out.println("  └─ 过期页面 Key: " + stalePageKey);

        System.out.println("\n📦 [初始状态] 本地缓存为空:");
        System.out.println("  └─ feedPublicCache.getIfPresent('" + stalePageKey + "') = "
                + (feedPublicCache.getIfPresent(stalePageKey) != null ? "❌ 意外存在" : "✅ 不存在（符合预期）"));

        when(mapper.findById(postId)).thenReturn(draftOwnedBy(postId, 1L));
        when(mapper.updateMetadata(any())).thenReturn(1);
        when(setOperations.members(indexKey)).thenReturn(Set.of(stalePageKey));
        when(setOperations.members("feed:public:index:" + postId + ":" + (hourSlot - 1))).thenReturn(Set.of());

        System.out.println("\n⚙️  [执行操作] 调用 service.updateMetadata()...");
        service.updateMetadata(1L, postId, "title", null, List.of(), List.of(), "public", false, "desc");

        System.out.println("\n✅ [验证结果]:");
        System.out.println("  └─ ✅ 即使本地没有缓存，也会清理 Redis Set 中的过期索引");
        
        verify(setOperations, times(2)).remove(indexKey, stalePageKey);

        System.out.println("     → Redis remove('" + stalePageKey + "') 被调用 2 次");
        System.out.println("\n🎉 [结论] 防止Redis Set索引泄漏！过期引用已被清理\n");
    }

    @Test
    void invalidateFeedLocalCache_shouldSkipNullAndBlankKeysSafely() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🧪 [TEST-3] Null/空值安全处理");
        System.out.println("=".repeat(80));

        long postId = 1003L;
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String indexKey = "feed:public:index:" + postId + ":" + hourSlot;
        String validPageKey = "feed:public:20:6:v1";
        java.util.HashSet<String> keysWithNulls = new java.util.HashSet<>();
        keysWithNulls.add(validPageKey);
        keysWithNulls.add(null);
        keysWithNulls.add("");

        System.out.println("\n📝 [准备阶段]");
        System.out.println("  ├─ Post ID: " + postId);
        System.out.println("  ├─ Redis 索引 Key: " + indexKey);
        System.out.println("  └─ 模拟脏数据集合: [" + validPageKey + ", null, \"\"]");

        when(mapper.findById(postId)).thenReturn(draftOwnedBy(postId, 1L));
        when(mapper.updateMetadata(any())).thenReturn(1);
        when(setOperations.members(indexKey)).thenReturn(keysWithNulls);
        when(setOperations.members("feed:public:index:" + postId + ":" + (hourSlot - 1))).thenReturn(Set.of());

        System.out.println("\n⚙️  [执行操作] 调用 service.updateMetadata()...");
        service.updateMetadata(1L, postId, "title", null, List.of(), List.of(), "public", false, "desc");

        System.out.println("\n✅ [验证结果] 安全处理检查:");
        System.out.println("  ├─ ✅ 有效 key '" + validPageKey + "' 已删除");
        System.out.println("  ├─ ✅ null 值未传给 Redis (安全跳过)");
        System.out.println("  └─ ✅ 空字符串未传给 Redis (安全跳过)");

        verify(setOperations, times(2)).remove(eq(indexKey), eq(validPageKey));
        verify(setOperations, never()).remove(eq(indexKey), isNull());
        verify(setOperations, never()).remove(eq(indexKey), eq(""));

        System.out.println("\n🎉 [结论] 脏数据处理正确！只清理有效数据，null/空串被安全忽略\n");
    }

    @Test
    void invalidateFeedLocalCache_shouldProcessBothCurrentAndPreviousHourSlots() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🧪 [TEST-4] 双时间窗口遍历（当前小时+前一小时）");
        System.out.println("=".repeat(80));

        long postId = 1004L;
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String currentIndexKey = "feed:public:index:" + postId + ":" + hourSlot;
        String previousIndexKey = "feed:public:index:" + postId + ":" + (hourSlot - 1);
        String currentPageKey = "feed:public:20:7:v1";
        String previousPageKey = "feed:public:20:8:v1";

        System.out.println("\n📝 [准备阶段]");
        System.out.println("  ├─ Post ID: " + postId);
        System.out.println("  ├─ 当前时间槽: " + hourSlot);
        System.out.println("  │   └─ 索引 Key: " + currentIndexKey);
        System.out.println("  ├─ 前一时间槽: " + (hourSlot - 1));
        System.out.println("  │   └─ 索引 Key: " + previousIndexKey);
        System.out.println("  └─ 模拟跨小时的缓存数据");

        feedPublicCache.put(currentPageKey, PageResponse.offset(List.of(), 7, 20, 0L));
        feedPublicCache.put(previousPageKey, PageResponse.offset(List.of(), 8, 20, 0L));

        System.out.println("\n📦 [初始状态] 两个时间窗口都有缓存:");
        System.out.println("  ├─ 当前小时缓存 '" + currentPageKey + "': ✅ 存在");
        System.out.println("  └─ 前一小时缓存 '" + previousPageKey + "': ✅ 存在");

        when(mapper.findById(postId)).thenReturn(draftOwnedBy(postId, 1L));
        when(mapper.updateMetadata(any())).thenReturn(1);
        when(setOperations.members(currentIndexKey)).thenReturn(Set.of(currentPageKey));
        when(setOperations.members(previousIndexKey)).thenReturn(Set.of(previousPageKey));

        System.out.println("\n⚙️  [执行操作] 调用 service.updateMetadata()...");
        service.updateMetadata(1L, postId, "title", null, List.of(), List.of(), "public", false, "desc");

        System.out.println("\n✅ [验证结果] 双时间窗口清理:");

        boolean currentCleared = feedPublicCache.getIfPresent(currentPageKey) == null;
        boolean previousCleared = feedPublicCache.getIfPresent(previousPageKey) == null;
        
        System.out.println("  ├─ ✅ 当前小时缓存清除: " + (currentCleared ? "成功 ✓" : "失败 ✗"));
        System.out.println("  ├─ ✅ 前一小时缓存清除: " + (previousCleared ? "成功 ✓" : "失败 ✗"));
        System.out.println("  └─ ✅ Redis 索引清理: 验证中...");

        assertThat(feedPublicCache.getIfPresent(currentPageKey)).isNull();
        assertThat(feedPublicCache.getIfPresent(previousPageKey)).isNull();
        verify(setOperations, times(2)).remove(currentIndexKey, currentPageKey);
        verify(setOperations, times(2)).remove(previousIndexKey, previousPageKey);

        System.out.println("     → 当前小时 Redis remove() × 2 次");
        System.out.println("     → 前一小时 Redis remove() × 2 次");
        System.out.println("\n🎉 [结论] 跨小时边界场景处理正确！两个时间窗口都被覆盖\n");
    }

    @Test
    void invalidateFeedLocalCache_shouldHandleEmptySetGracefully() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🧪 [TEST-5] 空 Set 边界条件处理");
        System.out.println("=".repeat(80));

        long postId = 1005L;
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String currentIndexKey = "feed:public:index:" + postId + ":" + hourSlot;
        String previousIndexKey = "feed:public:index:" + postId + ":" + (hourSlot - 1);

        System.out.println("\n📝 [准备阶段]");
        System.out.println("  ├─ Post ID: " + postId);
        System.out.println("  ├─ 当前时间槽索引: " + currentIndexKey + " (空)");
        System.out.println("  └─ 前一时间槽索引: " + previousIndexKey + " (空)");

        System.out.println("\n📦 [初始状态] 该帖子尚未被任何 feed 流缓存");
        System.out.println("  └─ Redis Set 返回空集合: Set.of()");

        when(mapper.findById(postId)).thenReturn(draftOwnedBy(postId, 1L));
        when(mapper.updateMetadata(any())).thenReturn(1);
        when(setOperations.members(currentIndexKey)).thenReturn(Set.of());
        when(setOperations.members(previousIndexKey)).thenReturn(Set.of());

        System.out.println("\n⚙️  [执行操作] 调用 service.updateMetadata()...");
        service.updateMetadata(1L, postId, "title", null, List.of(), List.of(), "public", false, "desc");

        System.out.println("\n✅ [验证结果] 边界条件处理:");
        System.out.println("  └─ ✅ 未调用任何 Redis remove 操作（避免无意义网络开销）");

        verify(setOperations, never()).remove(anyString(), anyString());

        System.out.println("\n🎉 [结论] 空Set优雅处理！不会触发多余的网络请求\n");
    }
}
