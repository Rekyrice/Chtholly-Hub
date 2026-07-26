package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.schema.CounterKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
@ExtendWith(MockitoExtension.class)
class CounterReactionProjectionRebuilderTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private CounterReactionMapper reactionMapper;
    @Mock
    private Cursor<String> cursor;

    private CounterReactionProjectionRebuilder rebuilder;

    @BeforeEach
    void setUp() {
        lenient().when(redis.delete(any(Collection.class))).thenReturn(0L);
        rebuilder = new CounterReactionProjectionRebuilder(redis, reactionMapper);
    }

    @Test
    void beginAtomicallyFencesTheEntityAndInvalidatesItsCompleteMarker() {
        when(redis.execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        rebuilder.begin("post", "7", "token");

        ArgumentCaptor<DefaultRedisScript<Long>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(script.capture(), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly(
                CounterKeys.factMaintenanceFenceKey("post", "7"),
                CounterKeys.reactionProjectionCompleteKey("post", "7"));
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('SET', fenceKey, token)")
                .contains("redis.call('DEL', completeKey)");
    }

    @Test
    void rebuildPagesMysqlFactsAcrossShardsAndReturnsAbsoluteCounts() {
        when(reactionMapper.listUserIdsAfter("post", "7", "like", 0L, 500))
                .thenReturn(List.of(1L, 32_769L));
        when(reactionMapper.listUserIdsAfter("post", "7", "fav", 0L, 500))
                .thenReturn(List.of(2L));
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(1L, 1L))
                .thenReturn(List.of(1L));
        doAnswer(invocation -> {
            DefaultRedisScript<?> script = invocation.getArgument(0);
            String source = script.getScriptAsString();
            if (source.contains("return {likeCount, favCount, nextEpoch}")) {
                return List.of(2L, 1L, 8L);
            }
            List<String> keys = invocation.getArgument(1);
            Object[] call = invocation.getArguments();
            int argumentCount = call.length - 2;
            if (source.contains("redis.call('RENAME'")) {
                return (long) (keys.size() - 1) / 2L;
            }
            if (source.contains("redis.call('SADD'")) {
                return (long) argumentCount - 1L;
            }
            if (source.contains("redis.call('SET', countKey")) {
                return Long.parseLong(String.valueOf(call[3]));
            }
            return 1L;
        }).when(redis).execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class));

        CounterReactionProjectionRebuilder.RebuildResult result =
                rebuilder.rebuild("post", "7", "token", 8L);

        assertThat(result)
                .isEqualTo(new CounterReactionProjectionRebuilder.RebuildResult(2L, 1L, 8L));
        verify(reactionMapper).listUserIdsAfter("post", "7", "like", 0L, 500);
        verify(reactionMapper).listUserIdsAfter("post", "7", "fav", 0L, 500);
        verify(redis, times(2)).executePipelined(any(SessionCallback.class));
        ArgumentCaptor<DefaultRedisScript<Long>> scripts =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis, times(8)).execute(
                scripts.capture(), anyList(), any(Object[].class));
        assertThat(scripts.getAllValues())
                .extracting(DefaultRedisScript::getScriptAsString)
                .anySatisfy(source -> assertThat(source).contains("redis.call('RENAME'"))
                .anySatisfy(source -> assertThat(source).contains("redis.call('SADD'"))
                .allSatisfy(source -> assertThat(source)
                        .contains("rebuild fence ownership lost"));
    }

    @Test
    void emptyMysqlFactsStillPublishCompleteEmptyIndexes() {
        when(reactionMapper.listUserIdsAfter("post", "7", "like", 0L, 500))
                .thenReturn(List.of());
        when(reactionMapper.listUserIdsAfter("post", "7", "fav", 0L, 500))
                .thenReturn(List.of());
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);
        doAnswer(invocation -> {
            DefaultRedisScript<?> script = invocation.getArgument(0);
            String source = script.getScriptAsString();
            if (source.contains(
                    "return {likeCount, favCount, nextEpoch}")) {
                return List.of(0L, 0L, 1L);
            }
            Object[] call = invocation.getArguments();
            int argumentCount = call.length - 2;
            if (source.contains("redis.call('SADD'")) {
                return (long) argumentCount - 1L;
            }
            if (source.contains("redis.call('SET', countKey")) {
                return Long.parseLong(String.valueOf(call[3]));
            }
            return 1L;
        }).when(redis).execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class));

        assertThat(rebuilder.rebuild("post", "7", "token", 1L))
                .isEqualTo(new CounterReactionProjectionRebuilder.RebuildResult(0L, 0L, 1L));

        ArgumentCaptor<DefaultRedisScript<Long>> scripts =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis, times(7)).execute(
                scripts.capture(), anyList(), any(Object[].class));
        assertThat(scripts.getAllValues())
                .extracting(DefaultRedisScript::getScriptAsString)
                .filteredOn(source -> source.contains("redis.call('SADD'"))
                .hasSize(2);
    }

    @Test
    void nullMysqlPageFailsBeforePublishingCompleteness() {
        when(reactionMapper.listUserIdsAfter("post", "7", "like", 0L, 500))
                .thenReturn(null);

        assertThatThrownBy(() -> rebuilder.rebuild("post", "7", "token", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL");

        verify(redis, times(0)).scan(any(ScanOptions.class));
    }

    @Test
    void abortInvalidatesCompletenessAndReleasesOnlyTheOwnedFence() {
        when(redis.execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        rebuilder.abort("post", "7", "token");

        verify(redis).delete(CounterKeys.reactionProjectionCompleteKey("post", "7"));
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(
                any(DefaultRedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue())
                .containsExactly(CounterKeys.factMaintenanceFenceKey("post", "7"));
    }
}
