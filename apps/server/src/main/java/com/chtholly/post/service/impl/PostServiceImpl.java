package com.chtholly.post.service.impl;

import com.chtholly.agent.content.ContentAnalysis;
import com.fasterxml.jackson.core.type.TypeReference;
import com.chtholly.counter.service.UserCounterService;
import com.chtholly.post.event.PostPublishedEvent;
import com.chtholly.post.service.PostFeedService;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.util.SlugUtils;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.api.dto.PostSummary;
import com.github.benmanes.caffeine.cache.Cache;
import com.chtholly.storage.config.OssProperties;
import com.chtholly.llm.rag.PostRagIndexer;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.tag.service.TagService;
import com.chtholly.search.index.SearchIndexService;
import com.chtholly.cache.hotkey.HotKeyDetector;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post lifecycle service: draft CRUD, publish, metadata, and detail retrieval.
 *
 * <p>Write path invalidates L1/L2 caches and emits Outbox events for search index sync.
 * Detail reads use Caffeine L1 → Redis L2 → MySQL with SingleFlight and hot-key TTL extension.
 *
 * @see PostFeedServiceImpl
 * @see SearchIndexService
 */
@Service
public class PostServiceImpl implements PostService {

    private final PostMapper mapper;
    @Resource
    private final SnowflakeIdGenerator idGen;
    private final ObjectMapper objectMapper;
    private final OssProperties ossProperties;
    private final UserCounterService userCounterService;
    private final StringRedisTemplate redis;
    @Qualifier("feedPublicCache")
    private final Cache<String, PageResponse<FeedItemResponse>> feedPublicCache;
    @Qualifier("postDetailCache")
    private final Cache<String, PostDetailResponse> postDetailCache;
    private final HotKeyDetector hotKey;
    private static final Logger log = LoggerFactory.getLogger(PostServiceImpl.class);
    private final PostDetailQueryService detailQueryService;
    private final PostRagIndexer ragIndexService;
    private final OutboxMapper outboxMapper;
    private final TagService tagService;
    private final SearchIndexService searchIndexService;
    private final ApplicationEventPublisher eventPublisher;
    private final PostFeedService postFeedService;

    // 手动编写构造器，Spring的@Qualifier直接标注在参数上（核心）
    public PostServiceImpl(
            PostMapper mapper,
            SnowflakeIdGenerator idGen,
            ObjectMapper objectMapper,
            OssProperties ossProperties,
            UserCounterService userCounterService,
            StringRedisTemplate redis,
            @Qualifier("feedPublicCache") Cache<String, PageResponse<FeedItemResponse>> feedPublicCache,
            @Qualifier("postDetailCache") Cache<String, PostDetailResponse> postDetailCache,
            HotKeyDetector hotKey,
            PostRagIndexer ragIndexService,
            OutboxMapper outboxMapper,
            TagService tagService,
            SearchIndexService searchIndexService,
            ApplicationEventPublisher eventPublisher,
            PostFeedService postFeedService,
            PostDetailQueryService detailQueryService
    ) {
        this.mapper = mapper;
        this.idGen = idGen;
        this.objectMapper = objectMapper;
        this.ossProperties = ossProperties;
        this.userCounterService = userCounterService;
        this.redis = redis;
        this.feedPublicCache = feedPublicCache;
        this.postDetailCache = postDetailCache; // 带@Qualifier的参数赋值
        this.hotKey = hotKey;
        this.ragIndexService = ragIndexService;
        this.outboxMapper = outboxMapper;
        this.tagService = tagService;
        this.searchIndexService = searchIndexService;
        this.eventPublisher = eventPublisher;
        this.postFeedService = postFeedService;
        this.detailQueryService = detailQueryService;
    }
    /**
     * Creates a new draft post and returns its snowflake ID.
     *
     * @param creatorId Owner user ID.
     * @return New post ID.
     */
    @Transactional
    public long createDraft(long creatorId) {
        long id = idGen.nextId();
        Instant now = Instant.now();
        Post post = Post.builder()
                .id(id)
                .creatorId(creatorId)
                .status("draft")
                .type("image_text")
                .visible("public")
                .isTop(false)
                .createTime(now)
                .updateTime(now)
                .build();
        mapper.insertDraft(post);
        return id;
    }

    /**
     * Returns recently published public posts for background agent cognition.
     *
     * @param window Lookback window.
     * @return Recent post summaries.
     */
    @Override
    public List<PostSummary> getRecentPosts(Duration window) {
        return getRecentPosts(window, 20);
    }

    @Override
    public List<PostSummary> getRecentPosts(Duration window, int limit) {
        Duration safeWindow = window == null || window.isNegative() || window.isZero()
                ? Duration.ofHours(6)
                : window;
        int safeLimit = Math.clamp(limit, 1, 500);
        Instant since = Instant.now().minus(safeWindow);
        return mapper.listRecentPublicSince(since, safeLimit).stream()
                .map(row -> new PostSummary(
                        row.getId(),
                        row.getTitle(),
                        row.getDescription(),
                        row.getPublishTime(),
                        parseStringArray(row.getTags())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostSummary> getPostSummariesByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Post> posts = mapper.findByIds(ids);
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        Map<Long, Post> byId = posts.stream()
                .filter(p -> p.getId() != null && "published".equals(p.getStatus()))
                .collect(java.util.stream.Collectors.toMap(Post::getId, p -> p, (a, b) -> a));
        List<PostSummary> ordered = new java.util.ArrayList<>();
        for (Long id : ids) {
            Post post = byId.get(id);
            if (post == null) {
                continue;
            }
            ordered.add(new PostSummary(
                    post.getId(),
                    post.getTitle(),
                    post.getDescription(),
                    post.getPublishTime(),
                    parseStringArray(post.getTags())));
        }
        return ordered;
    }

    /**
     * Returns recently published public seed posts for Chtholly audit jobs.
     *
     * @param window Lookback window.
     * @return Recent seed posts.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Post> getRecentSeedPosts(Duration window) {
        Duration safeWindow = window == null || window.isNegative() || window.isZero()
                ? Duration.ofHours(24)
                : window;
        return mapper.listRecentSeedPosts(Instant.now().minus(safeWindow), 50);
    }

    @Override
    public long countSince(Duration window) {
        Duration safeWindow = window == null || window.isNegative() || window.isZero()
                ? Duration.ofDays(3)
                : window;
        return mapper.countPublicSince(Instant.now().minus(safeWindow));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> listFirstTimePublisherIds(Duration window) {
        Duration safeWindow = window == null || window.isNegative() || window.isZero()
                ? Duration.ofDays(7)
                : window;
        return mapper.listFirstTimePublisherIdsSince(Instant.now().minus(safeWindow));
    }

    /**
     * Loads public posts whose Agent content understanding is missing or stale.
     *
     * @return posts to analyze in the scheduled understanding job.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Post> getPostsNeedingUnderstanding() {
        return mapper.listPostsNeedingUnderstanding(20);
    }

    /**
     * Stores Agent content understanding JSON on the post row.
     *
     * @param postId   post ID.
     * @param analysis content understanding result.
     */
    @Override
    @Transactional
    public void saveContentAnalysis(Long postId, ContentAnalysis analysis) {
        if (postId == null || analysis == null) {
            return;
        }
        try {
            mapper.updateContentAnalysis(
                    postId,
                    objectMapper.writeValueAsString(analysis),
                    analysis.analyzedAt() == null ? Instant.now() : analysis.analyzedAt());
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内容理解结果序列化失败");
        }
    }

    /**
     * Loads stored Agent content understanding for a post.
     *
     * @param postId post ID.
     * @return content analysis, or null when absent.
     */
    @Override
    @Transactional(readOnly = true)
    public ContentAnalysis getContentAnalysis(Long postId) {
        if (postId == null) {
            return null;
        }
        String json = mapper.findContentAnalysisById(postId);
        return parseContentAnalysisJson(postId, json);
    }

    /**
     * Loads stored Agent content understanding for a public post slug.
     *
     * @param slug post URL slug.
     * @return content analysis, or null when absent.
     */
    @Override
    @Transactional(readOnly = true)
    public ContentAnalysis getContentAnalysisBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        String json = mapper.findContentAnalysisBySlug(slug.trim());
        return parseContentAnalysisJson(slug, json);
    }

    private ContentAnalysis parseContentAnalysisJson(Object source, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ContentAnalysis.class);
        } catch (Exception e) {
            log.warn("Post content analysis deserialize failed, source={}: {}", source, e.getMessage());
            return null;
        }
    }

    /**
     * Confirms OSS content upload and stores object metadata on the draft.
     *
     * @param creatorId  Owner user ID.
     * @param id           Post ID.
     * @param objectKey    OSS object key.
     * @param etag         OSS ETag.
     * @param size         Content size in bytes.
     * @param sha256       Content checksum.
     */
    @Transactional
    public void confirmContent(long creatorId, long id, String objectKey, String etag, Long size, String sha256) {
        // 双删缓存：写前删一次、写后再删，降低并发读读到旧详情的窗口
        invalidateCache(id);

        Post post = Post.builder()
                .id(id)
                .creatorId(creatorId)
                .contentObjectKey(objectKey)
                .contentEtag(etag)
                .contentSize(size)
                .contentSha256(sha256)
                .contentUrl(publicUrl(objectKey))
                .updateTime(Instant.now())
                .build();

        int updated = mapper.updateContent(post);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);

        // 触发一次预索引（草稿阶段可能因可见性/状态被跳过）
        try {
            ragIndexService.ensureIndexed(id);
        } catch (Exception e) {
            log.warn("Pre-index after content confirm failed, post {}: {}", id, e.getMessage());
        }
    }

    /**
     * Updates post metadata (title, tags, visibility, pin, description).
     */
    @Transactional
    public void updateMetadata(long creatorId, long id, String title, Long tagId, List<String> tags, List<String> imgUrls, String visible, Boolean isTop, String description) {
        invalidateCache(id);

        Post existing = mapper.findById(id);
        if (existing == null || !existing.getCreatorId().equals(creatorId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        List<String> oldTags = parseStringArray(existing.getTags());
        boolean wasPublished = "published".equals(existing.getStatus());

        Post post = Post.builder()
                .id(id)
                .creatorId(creatorId)
                .title(title)
                .tagId(tagId)
                .tags(toJsonOrNull(tags))
                .imgUrls(toJsonOrNull(imgUrls))
                .visible(visible)
                .isTop(isTop)
                .description(description)
                .type("image_text")
                .updateTime(Instant.now())
                .build();

        int updated = mapper.updateMetadata(post);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        if (wasPublished) {
            tagService.syncPublishedPostTags(creatorId, oldTags, tags);
            syncSearchIndexUpsert(id);
        }

        // 元数据变更后写入 Outbox 事件，驱动搜索索引更新
        try {
            long outId = idGen.nextId();
            String payload = objectMapper.writeValueAsString(Map.of("entity", "post", "op", "upsert", "id", id));
            outboxMapper.insert(outId, "post", id, "PostMetadataUpdated", payload);
        } catch (Exception e) {
            log.warn("Outbox event after metadata update failed, post {}: {}", id, e.getMessage());
        }

        invalidateCache(id);
    }

    /** Publishes a draft: assigns slug, syncs tags, increments user post counter, indexes search/RAG. */
    @Transactional
    public void publish(long creatorId, long id) {
        int updated = mapper.publish(id, creatorId);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        Post post = mapper.findById(id);
        if (post != null && (post.getSlug() == null || post.getSlug().isBlank())) {
            String base = SlugUtils.fromTitle(post.getTitle());
            String unique = SlugUtils.ensureUnique(base, id, mapper::findIdBySlug);
            mapper.updateSlug(id, creatorId, unique);
            post = mapper.findById(id);
        }

        if (post != null) {
            tagService.syncPublishedPostTags(creatorId, List.of(), parseStringArray(post.getTags()));
        }

        try {
            userCounterService.incrementPosts(creatorId, 1);
        } catch (Exception e) {
            log.warn("Increment posts counter failed after publish, userId={}, postId={}: {}",
                    creatorId, id, e.getMessage());
        }

        // 写入 Outbox 事件，驱动搜索索引增量更新
        try {
            long outId = idGen.nextId();
            String payload = objectMapper.writeValueAsString(Map.of("entity", "post", "op", "upsert", "id", id));
            outboxMapper.insert(outId, "post", id, "PostPublished", payload);
        } catch (Exception e) {
            log.warn("Outbox event after publish failed, post {}: {}", id, e.getMessage());
        }

        syncSearchIndexUpsert(id);

        // 发布成功后触发一次预索引，减少首次问答冷启动
        try {
            ragIndexService.ensureIndexed(id);
        } catch (Exception e) {
            log.warn("Pre-index after publish failed, post {} (RAG backfill may recover): {}", id, e.getMessage(), e);
        }

        if (post != null && post.getPublishTime() != null) {
            try {
                eventPublisher.publishEvent(new PostPublishedEvent(
                        id, creatorId, post.getPublishTime(), post.getVisible()));
            } catch (Exception e) {
                log.warn("PostPublishedEvent failed, postId={}: {}", id, e.getMessage());
            }
        }
    }

    /** Sets or clears pin status for the author's post. */
    @Transactional
    public void updateTop(long creatorId, long id, boolean isTop) {
        invalidateCache(id);

        int updated = mapper.updateTop(id, creatorId, isTop);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);
        // 置顶会改排序与 isTop 标记，必须立刻丢掉 feed:mine 旧页
        postFeedService.invalidateMyPublishedCache(creatorId);
    }

    /** Updates visibility (public/followers/school/private/unlisted). */
    @Transactional
    public void updateVisibility(long creatorId, long id, String visible) {
        if (!isValidVisible(visible)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "可见性取值非法");
        }

        invalidateCache(id);

        int updated = mapper.updateVisibility(id, creatorId, visible);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);
        postFeedService.invalidateMyPublishedCache(creatorId);
    }

    /** Soft-deletes a post and removes it from search index when previously published. */
    @Transactional
    public void delete(long creatorId, long id) {
        invalidateCache(id);
        postFeedService.invalidateMyPublishedCache(creatorId);

        Post existing = mapper.findById(id);
        if (existing == null || !existing.getCreatorId().equals(creatorId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        boolean wasPublished = "published".equals(existing.getStatus());
        List<String> oldTags = parseStringArray(existing.getTags());

        int updated = mapper.softDelete(id, creatorId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        if (wasPublished) {
            tagService.releasePublishedPostTags(oldTags);
        }

        // 写入 Outbox 事件，驱动搜索索引软删
        try {
            long outId = idGen.nextId();
            String payload = objectMapper.writeValueAsString(Map.of("entity", "post", "op", "delete", "id", id));
            outboxMapper.insert(outId, "post", id, "PostDeleted", payload);
        } catch (Exception e) {
            log.warn("Outbox event after delete failed, post {}: {}", id, e.getMessage());
        }

        if (wasPublished) {
            syncSearchIndexDelete(id);
        }

        invalidateCache(id);
    }

    /** 管理员修改帖子可见性（不校验作者）。 */
    @Transactional
    @Override
    public void adminUpdateVisibility(long id, String visible) {
        if (!isValidVisible(visible)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "可见性取值非法");
        }
        invalidateCache(id);
        int updated = mapper.updateVisibilityById(id, visible);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "帖子不存在");
        }
        invalidateCache(id);
    }

    /** 管理员软删帖子（不校验作者）。 */
    @Transactional
    @Override
    public void adminDelete(long id) {
        invalidateCache(id);
        Post existing = mapper.findById(id);
        if (existing == null || "deleted".equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "帖子不存在");
        }
        boolean wasPublished = "published".equals(existing.getStatus());
        List<String> oldTags = parseStringArray(existing.getTags());

        int updated = mapper.softDeleteById(id);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "帖子不存在");
        }

        if (wasPublished) {
            tagService.releasePublishedPostTags(oldTags);
        }

        try {
            long outId = idGen.nextId();
            String payload = objectMapper.writeValueAsString(Map.of("entity", "post", "op", "delete", "id", id));
            outboxMapper.insert(outId, "post", id, "PostDeleted", payload);
        } catch (Exception e) {
            log.warn("Outbox event after admin delete failed, post {}: {}", id, e.getMessage());
        }

        if (wasPublished) {
            syncSearchIndexDelete(id);
        }

        invalidateCache(id);
    }

    /** Canal 关闭时 Outbox 不经 Kafka，本地直连 ES 索引（与 Consumer 幂等）。 */
    private void syncSearchIndexUpsert(long id) {
        try {
            searchIndexService.upsertPost(id);
        } catch (Exception e) {
            log.warn("Search index upsert failed, post {} (will retry on backfill): {}", id, e.getMessage(), e);
        }
    }

    private void syncSearchIndexDelete(long id) {
        try {
            searchIndexService.softDeletePost(id);
        } catch (Exception e) {
            log.warn("Search index delete failed, post {} (will retry on backfill): {}", id, e.getMessage(), e);
        }
    }

    private boolean isValidVisible(String visible) {
        if (visible == null) {
            return false;
        }

        return switch (visible) {
            case "public", "followers", "school", "private", "unlisted" -> true;
            default -> false;
        };
    }

    private String toJsonOrNull(List<String> list) {
        if (list == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JSON 处理失败");
        }
    }

    private String publicUrl(String objectKey) {
        String publicDomain = ossProperties.getPublicDomain();

        if (publicDomain != null && !publicDomain.isBlank()) {
            return publicDomain.replaceAll("/$", "") + "/" + objectKey;
        }

        return "https://" + ossProperties.getBucket() + "." + ossProperties.getEndpoint() + "/" + objectKey;
    }

    @Override
    public PostDetailResponse getDetail(long id, Long currentUserIdNullable) {
        return detailQueryService.getDetail(id, currentUserIdNullable);
    }

    @Override
    public PostDetailResponse getDetailBySlug(String slug, Long currentUserIdNullable) {
        return detailQueryService.getDetailBySlug(slug, currentUserIdNullable);
    }

    @Override
    public String computeDetailEtag(long id) {
        return detailQueryService.computeEtag(id);
    }

    @Override
    public String computeDetailEtagBySlug(String slug) {
        return detailQueryService.computeEtagBySlug(slug);
    }

    private void invalidateCache(long id) {
        String pageKey = PostDetailQueryService.cacheKey(id);

        try {
            redis.delete(pageKey);
        } catch (Exception e) {
            log.warn("Redis 详情缓存删除失败，key={}", pageKey, e);
        }

        try {
            postDetailCache.invalidate(pageKey);
        } catch (Exception e) {
            log.warn("本地详情缓存删除失败，key={}", pageKey, e);
        }

        try {
            invalidateFeedLocalCache(id);
        } catch (Exception e) {
            log.warn("Feed 本地缓存清理失败，id={}，将依赖 TTL 自动过期", id, e);
        }
    }

    private void invalidateFeedLocalCache(long id) {
        long hourSlot = System.currentTimeMillis() / 3600000L;
        for (long slot : List.of(hourSlot, hourSlot - 1)) {
            String indexKey = "feed:public:index:" + id + ":" + slot;
            try {
                Set<String> pageKeys = redis.opsForSet().members(indexKey);
                if (pageKeys == null || pageKeys.isEmpty()) {
                    continue;
                }
                for (String localPageKey : pageKeys) {
                    if (localPageKey == null || localPageKey.isBlank()) {
                        continue;
                    }
                    feedPublicCache.invalidate(localPageKey);
                    redis.opsForSet().remove(indexKey, localPageKey);
                }
            } catch (Exception e) {
                log.warn("Feed 缓存清理异常，indexKey={}", indexKey, e);
            }
        }
    }

    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
