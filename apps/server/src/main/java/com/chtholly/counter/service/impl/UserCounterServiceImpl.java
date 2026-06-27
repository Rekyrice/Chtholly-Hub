package com.chtholly.counter.service.impl;

import com.chtholly.counter.schema.UserCounterKeys;
import com.chtholly.counter.service.CounterService;
import com.chtholly.counter.service.UserCounterService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * User-level counter service (follow/fan/post/like-received aggregates in SDS).
 *
 * <p>Maintained asynchronously from relation and counter events; supports on-demand rebuild
 * by scanning posts and relation tables when SDS is corrupt or missing.
 */
@Service
public class UserCounterServiceImpl implements UserCounterService {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> incrScript;
    private final PostMapper postMapper;
    private final CounterService counterService;
    private final RelationMapper relationMapper;

    public UserCounterServiceImpl(StringRedisTemplate redis,
                                  PostMapper postMapper,
                                  CounterService counterService,
                                  RelationMapper relationMapper) {
        this.redis = redis;
        this.postMapper = postMapper;
        this.counterService = counterService;
        this.relationMapper = relationMapper;
        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        // 用户维度计数原子折叠（1 基坐标）
        this.incrScript.setScriptText(INCR_FIELD_LUA);
    }

    /** Atomically increments the user's following count in SDS segment 1. */
    @Override
    public void incrementFollowings(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "1", String.valueOf(delta));
    }

    /** Atomically increments the user's follower count in SDS segment 2. */
    @Override
    public void incrementFollowers(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "2", String.valueOf(delta));
    }

    /** Atomically increments the user's published post count in SDS segment 3. */
    @Override
    public void incrementPosts(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "3", String.valueOf(delta));
    }

    /** Atomically increments likes received across the user's posts (SDS segment 4). */
    @Override
    public void incrementLikesReceived(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "4", String.valueOf(delta));
    }

    /** Atomically increments favorites received across the user's posts (SDS segment 5). */
    @Override
    public void incrementFavsReceived(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "5", String.valueOf(delta));
    }

    /**
     * Rebuilds all user-level counters from relation and post tables (full scan).
     *
     * @param userId User whose SDS should be recomputed.
     */
    @Override
    public void rebuildAllCounters(long userId) {
        String key = UserCounterKeys.sdsKey(userId);
        byte[] raw = redis.execute((RedisCallback<byte[]>) c -> c.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
        int len = 5 * 4;
        byte[] buf = new byte[len];
        if (raw != null && raw.length == len) {
            // 保留已存在的值，按需覆盖
            System.arraycopy(raw, 0, buf, 0, len);
        }
        // 从数据库读取 关注数、粉丝数
        long followings = relationMapper.countFollowingActive(userId);
        long followers = relationMapper.countFollowerActive(userId);

        long posts;
        List<Long> ids = postMapper.listMyPublishedIds(userId);
        // 将 ids 转换成字符串类型的 List
        List<String> idStr = ids.stream()
                .map(String::valueOf)
                .collect(Collectors.toList());

        if (!idStr.isEmpty()) {
            posts = idStr.size();
            long likeSum = 0L;
            long favSum = 0L;
            Map<String, Map<String, Long>> counts = counterService.getCountsBatch("post", idStr, List.of("like", "fav"));
            for (String id : idStr) { // 聚合作者全部帖子的获赞/获收藏总数
                Map<String, Long> v = counts.get(id);
                likeSum += v.getOrDefault("like", 0L);
                favSum += v.getOrDefault("fav", 0L);
            }
            write32be(buf, 2 * 4, posts);
            write32be(buf, 3 * 4, likeSum);
            write32be(buf, 4 * 4, favSum);
        } else {
            write32be(buf, 2 * 4, 0L);
            write32be(buf, 3 * 4, 0L);
            write32be(buf, 4 * 4, 0L);
        }
        write32be(buf, 0, followings);
        write32be(buf, 4, followers);

        // 回写用户计数 SDS
        redis.execute((RedisCallback<Void>) c -> {
            c.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), buf);
            return null;
        });
    }

    private static final String INCR_FIELD_LUA = """
            
            local cntKey = KEYS[1]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2])
            local idx = tonumber(ARGV[3])
            local delta = tonumber(ARGV[4])
            local function read32be(s, off)
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end
            local function write32be(n)
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              return string.char(unpack(t))
            end
            local cnt = redis.call('GET', cntKey)
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            local off = (idx - 1) * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            redis.call('SET', cntKey, cnt)
            return 1
            """;

    private static long read32be(byte[] buf, int off) {
        if (buf == null || buf.length < off + 4) return 0L;
        long n = 0L;
        for (int i = 0; i < 4; i++) n = (n << 8) | (buf[off + i] & 0xFFL);
        return n;
    }

    private static void write32be(byte[] buf, int off, long val) {
        long n = Math.max(0, Math.min(val, 0xFFFF_FFFFL));
        buf[off] = (byte) ((n >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((n >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((n >>> 8) & 0xFF);
        buf[off + 3] = (byte) (n & 0xFF);
    }
}

