package com.chtholly.relation.service.impl;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.common.api.pagination.Pagination;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.service.RelationService;
import com.chtholly.relation.util.RelationCursor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.relation.event.RelationEvent;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.notification.event.FollowCreatedEvent;
import com.chtholly.relation.event.FollowCanceledEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.chtholly.user.mapper.UserMapper;
import com.chtholly.user.domain.User;
import com.chtholly.common.util.SensitiveDataUtil;
import com.chtholly.profile.api.dto.ProfileResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.sql.Timestamp;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.RedisCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follow/unfollow service with Outbox-driven fan-list sync and Redis ZSet read cache.
 *
 * <p>Write path: token-bucket rate limit → following table → Outbox event for async fan ZSet build.
 * Read path: L1 Caffeine Top cache for big-V users → Redis ZSet → DB backfill with short TTL.
 *
 * @see OutboxMapper
 */
@Service
public class RelationServiceImpl implements RelationService {
    private static final Logger log = LoggerFactory.getLogger(RelationServiceImpl.class);

    private final RelationMapper mapper;
    private final OutboxMapper outboxMapper;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> tokenScript;
    private final ObjectMapper objectMapper;
    private final Cache<Long, List<Long>> flwsTopCache;
    private final Cache<Long, List<Long>> fansTopCache;
    private final UserMapper userMapper;
    private final ApplicationEventPublisher eventPublisher;
    

    /**
     * 关系服务实现构造函数。
     * @param mapper 关系表数据访问
     * @param outboxMapper Outbox 事件写入访问
     * @param redis Redis 客户端
     * @param objectMapper JSON 序列化器
     */
    public RelationServiceImpl(RelationMapper mapper,
                               OutboxMapper outboxMapper,
                               StringRedisTemplate redis,
                               ObjectMapper objectMapper,
                               UserMapper userMapper,
                               ApplicationEventPublisher eventPublisher) {
        this.mapper = mapper;
        this.outboxMapper = outboxMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.tokenScript = new DefaultRedisScript<>();
        this.tokenScript.setResultType(Long.class);
        this.tokenScript.setScriptText(TOKEN_BUCKET_LUA);
        this.flwsTopCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(10)).build();
        this.fansTopCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(10)).build();
        this.userMapper = userMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 关注操作，限流通过令牌桶，并写入 Outbox 以异步构建缓存与粉丝表。
     * @param fromUserId 发起关注的用户ID
     * @param toUserId 被关注的用户ID
     * @return 是否关注成功
     */
    @Override
    @Transactional
    public boolean follow(long fromUserId, long toUserId) {
        // Lua 脚本令牌桶限流
        Long ok = redis.execute(tokenScript, List.of("rl:follow:" + fromUserId), "100", "1");
        if (ok == 0L) {
            return false;
        }

        long id = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        int inserted = mapper.insertFollowing(id, fromUserId, toUserId, 1);

        if (inserted > 0) {
            // Outbox 与 following 使用同一本地事务，fan 侧再由 CDC/Consumer 最终一致更新。
            writeRelationOutbox(id, "FollowCreated",
                    new RelationEvent("FollowCreated", fromUserId, toUserId, id));

            User actor = userMapper.findById(fromUserId);
            eventPublisher.publishEvent(new FollowCreatedEvent(
                    fromUserId,
                    actor == null ? null : actor.getNickname(),
                    actor == null ? null : actor.getAvatar(),
                    toUserId
            ));

            return true;
        }
        return false;
    }

    /**
     * 取消关注操作，并写入 Outbox 事件。
     * @param fromUserId 发起取消关注的用户ID
     * @param toUserId 被取消关注的用户ID
     * @return 是否取消成功
     */
    @Override
    @Transactional
    public boolean unfollow(long fromUserId, long toUserId) {
        int updated = mapper.cancelFollowing(fromUserId, toUserId);
        if (updated > 0) {
            writeRelationOutbox(null, "FollowCanceled",
                    new RelationEvent("FollowCanceled", fromUserId, toUserId, null));
            try {
                eventPublisher.publishEvent(new FollowCanceledEvent(fromUserId, toUserId));
            } catch (Exception e) {
                log.warn("FollowCanceledEvent failed, from={}, to={}: {}", fromUserId, toUserId, e.getMessage());
            }
            return true;
        }
        return false;
    }

    /**
     * 判断是否已关注。
     * @param fromUserId 关注发起者
     * @param toUserId 被关注者
     * @return 是否已关注
     */
    @Override
    public boolean isFollowing(long fromUserId, long toUserId) {
        return mapper.existsFollowing(fromUserId, toUserId) > 0;
    }

    /** Writes the relation event in the caller's local transaction. */
    private void writeRelationOutbox(Long aggregateId, String eventType, RelationEvent event) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize relation Outbox event " + eventType, e);
        }
        long outboxId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        outboxMapper.insert(outboxId, "following", aggregateId, eventType, payload);
    }

    /**
     * 获取关注列表（偏移分页），优先读取 Redis ZSet，未命中时回填并设置 TTL。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量
     * @return 关注的用户ID列表
     */
    @Override
    public List<Long> following(long userId, int limit, int offset) {
        String key = "uf:flws:" + userId;
        return getListWithOffset(
                key,
                offset,
                limit,
                need -> mapper.listFollowingRows(userId, need, 0),
                "toUserId",
                "createdAt",
                flwsTopCache,
                userId
        );
    }

    /**
     * 获取粉丝列表（偏移分页），ZSet 优先，DB 回填并设置 TTL。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量
     * @return 粉丝用户ID列表
     */
    @Override
    public List<Long> followers(long userId, int limit, int offset) {
        String key = "uf:fans:" + userId;
        return getListWithOffset(
                key,
                offset,
                limit,
                need -> mapper.listFollowerRows(userId, need, 0),
                "fromUserId",
                "createdAt",
                fansTopCache,
                userId
        );
    }

    /**
     * 查询双方关系状态。
     * @param userId 当前用户ID
     * @param otherUserId 对方用户ID
     * @return 三态关系：following/followedBy/mutual
     */
    @Override
    public Map<String, Boolean> relationStatus(long userId, long otherUserId) {
        boolean following = isFollowing(userId, otherUserId);
        boolean followedBy = isFollowing(otherUserId, userId);
        boolean mutual = following && followedBy;
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("following", following);
        m.put("followedBy", followedBy);
        m.put("mutual", mutual);
        return m;
    }

    /**
     * 游标分页获取关注列表，按创建时间倒序基于 ZSet 分数。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param cursor 上一页末条的分数（毫秒时间戳），为空代表第一页
     * @return 关注的用户ID列表
     */
    @Override
    public List<Long> followingCursor(long userId, int limit, Long cursor) {
        String key = "uf:flws:" + userId;
        return getListWithCursor(
                key,
                limit,
                cursor,
                need -> mapper.listFollowingRows(userId, need, 0),
                "toUserId",
                "createdAt"
        );
    }

    /**
     * 游标分页获取粉丝列表。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param cursor 上一页末条的分数（毫秒时间戳），为空代表第一页
     * @return 粉丝用户ID列表
     */
    @Override
    public List<Long> followersCursor(long userId, int limit, Long cursor) {
        String key = "uf:fans:" + userId;
        return getListWithCursor(
                key,
                limit,
                cursor,
                need -> mapper.listFollowerRows(userId, need, 0),
                "fromUserId",
                "createdAt"
        );
    }

    @Override
    public PageResponse<ProfileResponse> followingProfilesPage(long userId, int size, String cursor,
                                                               Integer legacyOffset, Long legacyCursorMs) {
        return profilesPage(userId, size, cursor, legacyOffset, legacyCursorMs, true);
    }

    @Override
    public PageResponse<ProfileResponse> followersProfilesPage(long userId, int size, String cursor,
                                                               Integer legacyOffset, Long legacyCursorMs) {
        return profilesPage(userId, size, cursor, legacyOffset, legacyCursorMs, false);
    }

    private PageResponse<ProfileResponse> profilesPage(long userId, int size, String cursor,
                                                         Integer legacyOffset, Long legacyCursorMs,
                                                         boolean following) {
        int safeSize = Pagination.clampSize(size);
        int fetch = safeSize + 1;
        String zsetKey = following ? "uf:flws:" + userId : "uf:fans:" + userId;

        if (legacyOffset != null && legacyOffset > 0 && !hasCursorInput(cursor, legacyCursorMs)) {
            int offset = Math.max(legacyOffset, 0);
            List<Long> ids = following
                    ? following(userId, fetch, offset)
                    : followers(userId, fetch, offset);
            boolean hasMore = ids.size() > safeSize;
            if (hasMore) {
                ids = ids.subList(0, safeSize);
            }
            int page = offset / safeSize + 1;
            return PageResponse.offset(toProfiles(ids), page, safeSize, 0L, hasMore);
        }

        Long cursorMs = resolveCursorMillis(cursor, legacyCursorMs);
        List<Long> ids = following
                ? followingCursor(userId, fetch, cursorMs)
                : followersCursor(userId, fetch, cursorMs);
        boolean hasMore = ids.size() > safeSize;
        if (hasMore) {
            ids = ids.subList(0, safeSize);
        }
        String nextCursor = null;
        if (hasMore && !ids.isEmpty()) {
            long lastId = ids.getLast();
            Double score = redis.opsForZSet().score(zsetKey, String.valueOf(lastId));
            if (score != null) {
                nextCursor = RelationCursor.encode(score.longValue(), lastId);
            }
        }
        return PageResponse.cursor(toProfiles(ids), safeSize, hasMore, nextCursor);
    }

    private static boolean hasCursorInput(String cursor, Long legacyCursorMs) {
        return (cursor != null && !cursor.isBlank()) || legacyCursorMs != null;
    }

    private static Long resolveCursorMillis(String cursor, Long legacyCursorMs) {
        if (cursor != null && !cursor.isBlank()) {
            return RelationCursor.decode(cursor)
                    .map(RelationCursor.RelationCursorPoint::createdAtMillis)
                    .orElse(null);
        }
        return legacyCursorMs;
    }

    /**
     * 将用户 ID 列表映射为资料视图列表（批量查询并保持输入顺序）。
     */
    private List<ProfileResponse> toProfiles(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<User> users = userMapper.listByIds(ids);
        Map<Long, User> m = new LinkedHashMap<>(users.size());
        for (User u : users) m.put(u.getId(), u);
        List<ProfileResponse> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            User u = m.get(id);
            if (u == null) continue;
            out.add(new ProfileResponse(u.getId(), u.getNickname(), u.getAvatar(), u.getBio(), u.getHandle(), u.getGender(), u.getBirthday(), u.getSchool(), SensitiveDataUtil.maskPhone(u.getPhone()), SensitiveDataUtil.maskEmail(u.getEmail()), u.getTagsJson()));
        }
        return out;
    }

    /**
     * 判断是否为大V（基于 followers 计数阈值）。
     * @param userId 用户ID
     * @return 是否为大V
     */
    private boolean isBigV(long userId) {
        byte[] raw = redis.execute((RedisCallback<byte[]>) c -> c.stringCommands().get(("ucnt:" + userId).getBytes(StandardCharsets.UTF_8)));

        if (raw == null || raw.length < 20) {
            return false;
        }

        long n = 0;
        int off = 2 * 4; // SDS 第 3 段（followers 计数）在大端 4 字节布局中的偏移

        for (int i = 0; i < 4; i++) {
            n = (n << 8) | (raw[off + i] & 0xFFL);
        }

        // 50 万粉丝阈值：超过后启用本地 Top-500 缓存，避免每次分页都扫完整 ZSet
        return n >= 500_000L;
    }

    /**
     * 偏移分页读取：优先命中 ZSet，未命中时从 DB 回填并设置 TTL；大V用户维护本地 Top 缓存以降低冷启动开销。
     */
    private List<Long> getListWithOffset(
            String key,
            int offset,
            int limit,
            IntFunction<Map<Long, Map<String, Object>>> rowsFetcher,
            String idField,
            String tsField,
            Cache<Long, List<Long>> localCache,
            long userId
    ) {
        // 1. 先查本地缓存 (L1)
        List<Long> top = localCache != null ? localCache.getIfPresent(userId) : null;
        if (top != null && !top.isEmpty()) {
            // 本地缓存通常只存 Top N (例如前500)，如果 offset 在范围内则直接返回
            if (offset < top.size()) {
                int to = Math.min(offset + limit, top.size());
                return new ArrayList<>(top.subList(offset, to));
            }
            // 如果请求的 offset 超过了本地缓存范围，继续查 Redis
        }

        // 2. 再查 Redis (L2)
        Set<String> cached = redis.opsForZSet().reverseRange(key, offset, offset + limit - 1L);
        if (cached != null && !cached.isEmpty()) {
            return toLongList(cached);
        }

        // 3. 最后查 DB 回填
        int need = Math.max(1, limit + offset);
        Map<Long, Map<String, Object>> rows = rowsFetcher.apply(Math.min(need, 1000));
        if (rows != null && !rows.isEmpty()) {
            fillZSet(key, rows, idField, tsField, null);
            redis.expire(key, Duration.ofHours(2));

            // 回填后尝试更新本地缓存（仅针对大V）
            if (localCache != null && isBigV(userId)) {
                maybeUpdateTopCache(userId, key, localCache);
            }

            Set<String> filled = redis.opsForZSet().reverseRange(key, offset, offset + limit - 1L);
            return filled == null ? Collections.emptyList() : toLongList(filled);
        }
        return Collections.emptyList();
    }

    /**
     * 游标分页读取：按分数（毫秒时间戳）倒序读取；未命中时回填满足所需范围的数据并继续读取。
     */
    private List<Long> getListWithCursor(String key,
                                         int limit,
                                         Long cursor,
                                         IntFunction<Map<Long, Map<String, Object>>> rowsFetcher,
                                         String idField,
                                         String tsField) {

        double max = cursor == null ? Double.POSITIVE_INFINITY : cursor.doubleValue();
        Set<String> cached = redis.opsForZSet().reverseRangeByScore(key, Double.NEGATIVE_INFINITY, max, 0, limit);

        if (cached != null && !cached.isEmpty()) {
            return toLongList(cached);
        }

        int need = Math.max(limit, 100);
        Map<Long, Map<String, Object>> rows = rowsFetcher.apply(Math.min(need, 1000));

        if (rows != null && !rows.isEmpty()) {
            fillZSet(key, rows, idField, tsField, cursor);
            redis.expire(key, Duration.ofHours(2));
            Set<String> filled = redis.opsForZSet().reverseRangeByScore(key, Double.NEGATIVE_INFINITY, max, 0, limit);
            return filled == null ? Collections.emptyList() : toLongList(filled);
        }
        return Collections.emptyList();
    }

    /**
     * 将行数据填充至 ZSet：分值为创建时间戳；若提供游标则只填充不高于游标的记录。
     */
    private void fillZSet(String key,
                          Map<Long, Map<String, Object>> rows,
                          String idField,
                          String tsField,
                          Long cursor) {
        for (Map<String, Object> r : rows.values()) {
            Object idObj = r.get(idField);
            Object tsObj = r.get(tsField);
            if (idObj == null || tsObj == null) continue;
            long score = tsScore(tsObj);
            if (cursor == null || score <= cursor) {
                redis.opsForZSet().add(key, String.valueOf(idObj), score);
            }
        }
    }

    /**
     * 将多类型时间对象统一转换为毫秒分值。
     */
    private long tsScore(Object tsObj) {
        if (tsObj instanceof Timestamp ts) {
            return ts.getTime();
        }
        if (tsObj instanceof Date d) {
            return d.getTime();
        }
        return System.currentTimeMillis();
    }

    /**
     * 将字符串集合按原顺序映射为长整型列表。
     */
    private List<Long> toLongList(Set<String> set) {
        List<Long> out = new ArrayList<>(set.size());
        for (String s : set) out.add(Long.valueOf(s));
        return out;
    }

    /**
     * 更新本地 Top 缓存：大V 用户仅缓存前 500 名，减少频繁回源与排序成本。
     */
    private void maybeUpdateTopCache(long userId, String key, Cache<Long, List<Long>> cache) {
        Set<String> allSet = redis.opsForZSet().reverseRange(key, 0, 499);
        if (allSet == null || allSet.isEmpty()) return;
        List<Long> all = new ArrayList<>(allSet.size());
        for (String s : allSet) all.add(Long.valueOf(s));
        cache.put(userId, all);
    }

    private static final String TOKEN_BUCKET_LUA = """
            
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = redis.call('TIME')[1]
            local last = redis.call('HGET', key, 'last')
            local tokens = redis.call('HGET', key, 'tokens')
            if not last then last = now; tokens = capacity end
            local elapsed = tonumber(now) - tonumber(last)
            local add = elapsed * rate
            tokens = math.min(capacity, tonumber(tokens) + add)
            if tokens < 1 then redis.call('HSET', key, 'last', now); redis.call('HSET', key, 'tokens', tokens); return 0 end
            tokens = tokens - 1
            redis.call('HSET', key, 'last', now)
            redis.call('HSET', key, 'tokens', tokens)
            redis.call('PEXPIRE', key, 60000)
            return 1
            """;
}
