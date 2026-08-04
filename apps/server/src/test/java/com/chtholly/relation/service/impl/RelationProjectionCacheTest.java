package com.chtholly.relation.service.impl;

import com.chtholly.relation.mapper.RelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Characterizes Redis-first relation list projection reads and DB backfill. */
@ExtendWith(MockitoExtension.class)
class RelationProjectionCacheTest {

    @Mock private RelationMapper relationMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private RelationCacheInvalidator relationCacheInvalidator;
    @Mock private ZSetOperations<String, String> sortedSets;

    private RelationProjectionCache projectionCache;

    @BeforeEach
    void setUp() {
        when(redis.opsForZSet()).thenReturn(sortedSets);
        projectionCache = new RelationProjectionCache(
                relationMapper, redis, relationCacheInvalidator);
    }

    @Test
    void offsetReadUsesRedisOrderWithoutDatabaseFallback() {
        when(sortedSets.reverseRange("uf:flws:11", 1, 2))
                .thenReturn(linkedSet("22", "33"));

        assertThat(projectionCache.following(11L, 2, 1))
                .containsExactly(22L, 33L);

        verify(relationMapper, never())
                .listFollowingRows(11L, 3, 0);
    }

    @Test
    void cacheMissBackfillsZSetAndKeepsTwoHourTtl() {
        when(sortedSets.reverseRange("uf:flws:11", 0, 1))
                .thenReturn(Set.of(), linkedSet("22", "33"));
        Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        rows.put(1L, Map.of(
                "toUserId", 22L,
                "createdAt", Timestamp.valueOf("2026-01-01 00:00:00")));
        rows.put(2L, Map.of(
                "toUserId", 33L,
                "createdAt", Timestamp.valueOf("2026-01-02 00:00:00")));
        when(relationMapper.listFollowingRows(11L, 2, 0))
                .thenReturn(rows);

        assertThat(projectionCache.following(11L, 2, 0))
                .containsExactly(22L, 33L);

        verify(sortedSets).add(
                "uf:flws:11",
                "22",
                Timestamp.valueOf("2026-01-01 00:00:00").getTime());
        verify(sortedSets).add(
                "uf:flws:11",
                "33",
                Timestamp.valueOf("2026-01-02 00:00:00").getTime());
        verify(redis).expire("uf:flws:11", Duration.ofHours(2));
    }

    @Test
    void topCacheBoundaryIsFilledFromTheSharedProjection() {
        when(relationCacheInvalidator.getFollowingTop(11L))
                .thenReturn(List.of(10L, 20L, 30L));
        when(sortedSets.reverseRange("uf:flws:11", 3, 4))
                .thenReturn(linkedSet("40", "50"));

        assertThat(projectionCache.following(11L, 3, 2))
                .containsExactly(30L, 40L, 50L);
    }

    private static LinkedHashSet<String> linkedSet(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}
