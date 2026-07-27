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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void explicitInvalidationHidesCompletenessBeforeManagedFactsCanChange() {
        String complete = CounterKeys.reactionProjectionCompleteKey("post", "7");
        when(redis.delete(complete)).thenReturn(true);

        rebuilder.invalidateComplete("post", "7");

        verify(redis).delete(complete);
    }

    @Test
    void publishCompleteAtomicallyRequiresOwnershipAndReleasesTheFence() {
        when(redis.execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        rebuilder.publishComplete("post", "7", "token");

        ArgumentCaptor<DefaultRedisScript<Long>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(script.capture(), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly(
                CounterKeys.factMaintenanceFenceKey("post", "7"),
                CounterKeys.reactionProjectionCompleteKey("post", "7"));
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('GET', fenceKey) ~= '@prepared:' .. token")
                .contains("redis.call('SET', completeKey, completeVersion)")
                .contains("redis.call('DEL', fenceKey)");
    }

    @Test
    void rebuildStreamsFiveHundredAndOneFactsWithoutCollectingAllShards() {
        List<Long> firstPage = LongStream.rangeClosed(1L, 500L).boxed().toList();
        when(reactionMapper.listUserIdsAfter("post", "7", "like", 0L, 500))
                .thenReturn(firstPage);
        when(reactionMapper.listUserIdsAfter("post", "7", "like", 500L, 500))
                .thenReturn(List.of(32_769L));
        when(reactionMapper.listUserIdsAfter("post", "7", "fav", 0L, 500))
                .thenReturn(List.of());
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(500L))
                .thenReturn(List.of(1L));
        stubOwnedScripts(List.of(501L, 0L, 8L));

        CounterReactionProjectionRebuilder.RebuildResult result =
                rebuilder.rebuild("post", "7", "token", 8L);

        assertThat(result)
                .isEqualTo(new CounterReactionProjectionRebuilder.RebuildResult(501L, 0L, 8L));
        verify(reactionMapper).listUserIdsAfter("post", "7", "like", 0L, 500);
        verify(reactionMapper).listUserIdsAfter("post", "7", "like", 500L, 500);
        verify(reactionMapper).listUserIdsAfter("post", "7", "fav", 0L, 500);
        verify(redis, times(2)).executePipelined(any(SessionCallback.class));
        verify(redis, times(2)).scan(any(ScanOptions.class));
        ArgumentCaptor<DefaultRedisScript<Long>> scripts =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis, times(5)).execute(
                scripts.capture(), anyList(), any(Object[].class));
        assertThat(scripts.getAllValues())
                .extracting(DefaultRedisScript::getScriptAsString)
                .noneSatisfy(source -> assertThat(source)
                        .containsAnyOf("redis.call('RENAME'", "stageKey"));
    }

    @Test
    void emptyMysqlFactsStillPrepareCompleteEmptyIndexes() {
        when(reactionMapper.listUserIdsAfter("post", "7", "like", 0L, 500))
                .thenReturn(List.of());
        when(reactionMapper.listUserIdsAfter("post", "7", "fav", 0L, 500))
                .thenReturn(List.of());
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);
        stubOwnedScripts(List.of(0L, 0L, 1L));

        assertThat(rebuilder.rebuild("post", "7", "token", 1L))
                .isEqualTo(new CounterReactionProjectionRebuilder.RebuildResult(0L, 0L, 1L));

        verify(redis, never()).executePipelined(any(SessionCallback.class));
        verify(redis, times(2)).scan(any(ScanOptions.class));
        verify(redis, times(5)).execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void staleShardCleanupUsesRepeatedScansAndBoundedDeleteBatches() {
        when(reactionMapper.listUserIdsAfter("post", "7", "like", 0L, 500))
                .thenReturn(List.of());
        when(reactionMapper.listUserIdsAfter("post", "7", "fav", 0L, 500))
                .thenReturn(List.of());
        List<String> staleKeys = LongStream.rangeClosed(0L, 500L)
                .mapToObj(chunk -> CounterKeys.bitmapKey(
                        "like", "post", "7", chunk))
                .toList();
        AtomicInteger cursorIndex = new AtomicInteger();
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenAnswer(ignored ->
                cursorIndex.get() < staleKeys.size());
        when(cursor.next()).thenAnswer(ignored ->
                staleKeys.get(cursorIndex.getAndIncrement()));
        stubOwnedScripts(List.of(0L, 0L, 1L));

        rebuilder.rebuild("post", "7", "token", 1L);

        verify(redis, times(3)).scan(any(ScanOptions.class));
        ArgumentCaptor<DefaultRedisScript<Long>> scripts =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keyBatches =
                ArgumentCaptor.forClass(List.class);
        verify(redis, times(7)).execute(
                scripts.capture(), keyBatches.capture(), any(Object[].class));
        List<Integer> deleteBatchSizes = LongStream.range(
                        0L, scripts.getAllValues().size())
                .filter(index -> scripts.getAllValues().get((int) index)
                        .getScriptAsString().contains("unpack(keys)"))
                .mapToObj(index -> keyBatches.getAllValues().get((int) index).size())
                .toList();
        assertThat(deleteBatchSizes).containsExactly(501, 2);
    }

    @Test
    void staleShardCleanupRepeatsScanningUntilNoLiveShardRemains() {
        when(reactionMapper.listUserIdsAfter("post", "7", "like", 0L, 500))
                .thenReturn(List.of());
        when(reactionMapper.listUserIdsAfter("post", "7", "fav", 0L, 500))
                .thenReturn(List.of());
        Cursor<String> firstLikePass = cursorWith(
                CounterKeys.bitmapKey("like", "post", "7", 1L));
        Cursor<String> secondLikePass = cursorWith(
                CounterKeys.bitmapKey("like", "post", "7", 2L));
        Cursor<String> emptyLikePass = cursorWith();
        Cursor<String> emptyFavoritePass = cursorWith();
        when(redis.scan(any(ScanOptions.class))).thenReturn(
                firstLikePass,
                secondLikePass,
                emptyLikePass,
                emptyFavoritePass);
        stubOwnedScripts(List.of(0L, 0L, 1L));

        rebuilder.rebuild("post", "7", "token", 1L);

        verify(redis, times(4)).scan(any(ScanOptions.class));
        ArgumentCaptor<DefaultRedisScript<Long>> scripts =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis, times(7)).execute(
                scripts.capture(), anyList(), any(Object[].class));
        assertThat(scripts.getAllValues())
                .extracting(DefaultRedisScript::getScriptAsString)
                .filteredOn(source -> source.contains("unpack(keys)"))
                .hasSize(2);
    }

    @Test
    void nullMysqlPageFailsBeforeMutatingRedis() {
        when(reactionMapper.listUserIdsAfter("post", "7", "like", 0L, 500))
                .thenReturn(null);

        assertThatThrownBy(() -> rebuilder.rebuild("post", "7", "token", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL");

        verify(redis, never()).scan(any(ScanOptions.class));
        verify(redis, never()).execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void abortInvalidatesOnlyTheCompleteMarkerOwnedByItsFenceToken() {
        when(redis.execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        rebuilder.abort("post", "7", "token");

        ArgumentCaptor<DefaultRedisScript<Long>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(script.capture(), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly(
                CounterKeys.factMaintenanceFenceKey("post", "7"),
                CounterKeys.reactionProjectionCompleteKey("post", "7"));
        assertThat(script.getValue().getScriptAsString())
                .contains("fenceValue ~= token")
                .contains("fenceValue ~= '@prepared:' .. token")
                .contains("fenceValue ~= '@dirty:' .. token")
                .contains("redis.call('DEL', completeKey)");
        verify(redis, never()).delete(any(String.class));
    }

    private void stubOwnedScripts(List<Long> finalizeResult) {
        doAnswer(invocation -> {
            DefaultRedisScript<?> script = invocation.getArgument(0);
            String source = script.getScriptAsString();
            if (source.contains("return {likeCount, favCount, nextEpoch}")) {
                return finalizeResult;
            }
            Object[] call = invocation.getArguments();
            if (source.contains("redis.call('SET', countKey")) {
                return Long.parseLong(String.valueOf(call[3]));
            }
            return 1L;
        }).when(redis).execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    private static Cursor<String> cursorWith(String... keys) {
        Cursor<String> result = mock(Cursor.class);
        AtomicInteger index = new AtomicInteger();
        when(result.hasNext()).thenAnswer(ignored -> index.get() < keys.length);
        if (keys.length > 0) {
            when(result.next()).thenAnswer(ignored -> keys[index.getAndIncrement()]);
        }
        return result;
    }
}
