package com.chtholly.relation.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RelationCacheInvalidatorTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RelationCacheInvalidator invalidator = new RelationCacheInvalidator(redis);

    @Test
    void givenAffectedUsers_whenInvalidate_thenDeletesFollowingAndFollowerCachesInOneBatch() {
        invalidator.invalidateUsers(List.of(42L, 7L, 42L));

        verify(redis).delete(List.of(
                "uf:flws:42", "uf:fans:42",
                "uf:flws:7", "uf:fans:7"));
    }

    @Test
    void givenEmptyUsers_whenInvalidate_thenDoesNotCallRedis() {
        invalidator.invalidateUsers(List.of());

        verifyNoInteractions(redis);
    }

    @Test
    void givenRedisFailure_whenInvalidate_thenPropagatesToImportBoundary() {
        List<String> keys = List.of("uf:flws:42", "uf:fans:42");
        doThrow(new IllegalStateException("Redis unavailable")).when(redis).delete(keys);

        assertThatThrownBy(() -> invalidator.invalidateUsers(List.of(42L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis unavailable");
    }

    @Test
    void givenInvalidUserId_whenInvalidate_thenRejectsBeforeRedis() {
        assertThatThrownBy(() -> invalidator.invalidateUsers(java.util.Arrays.asList(42L, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> invalidator.invalidateUsers(List.of(0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> invalidator.invalidateUsers(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userIds");

        verifyNoInteractions(redis);
    }
}
