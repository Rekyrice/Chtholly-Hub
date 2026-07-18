package com.chtholly.seed.contentpack;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterEventPublisher;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterService;
import com.chtholly.counter.service.CounterFactMaintenanceService;
import com.chtholly.counter.service.CounterFactMaintenanceService.ManagedPostReactionState;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.ResolvedIdentities;
import com.chtholly.seed.contentpack.model.SeedReactionDefinition;
import com.chtholly.seed.contentpack.model.SeedViewDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ContentPackReactionApplierTest {

    @Mock
    private CounterService counterService;
    @Mock
    private CounterFactMaintenanceService factMaintenanceService;
    @Mock
    private CounterEventPublisher eventPublisher;
    @Mock
    private RedissonClient redisson;
    @Mock
    private RLock viewLock;

    private ContentPackReactionApplier applier;
    private ResolvedIdentities identities;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redisson.getLock("lock:seed-content:view-baseline:post:99")).thenReturn(viewLock);
        lenient().when(viewLock.tryLock(5L, 5L, TimeUnit.SECONDS)).thenReturn(true);
        lenient().when(viewLock.isHeldByCurrentThread()).thenReturn(true);
        applier = new ContentPackReactionApplier(
                counterService, factMaintenanceService, eventPublisher, redisson, 3, Duration.ZERO);
        identities = new ResolvedIdentities(
                "launch-community", Map.of("author", 42L, "reader", 43L), Map.of("post-one", 99L));
    }

    @Test
    void givenDeclaredLikesAndFavorites_whenApply_thenSilentlyReconcilesCompleteManagedFacts() {
        List<SeedReactionDefinition> reactions = List.of(
                new SeedReactionDefinition("like-one", "post-one", "reader", "like"),
                new SeedReactionDefinition("fav-one", "post-one", "reader", "fav"));

        applier.apply(reactions, List.of(), identities);

        verify(factMaintenanceService).reconcileManagedPostReactions(
                Set.of(42L, 43L), Set.of(99L),
                Map.of(99L, new ManagedPostReactionState(Set.of(43L), Set.of(43L))));
        verify(counterService, never()).like(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(counterService, never()).fav(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void givenOwnerPublicPostSlug_whenApply_thenUsesResolvedExternalPostId() {
        ResolvedIdentities withOwnerPost = new ResolvedIdentities(
                identities.namespace(), identities.accountIds(), identities.postIds(), Map.of("owner-note", 808L));
        SeedReactionDefinition reaction = new SeedReactionDefinition(
                "owner-like", null, "owner-note", "reader", "like");

        applier.apply(List.of(reaction), List.of(), withOwnerPost);

        verify(factMaintenanceService).reconcileManagedPostReactions(
                Set.of(42L, 43L), Set.of(99L, 808L),
                Map.of(808L, new ManagedPostReactionState(Set.of(43L), Set.of())));
    }

    @Test
    void givenUndeclaredExistingReaction_whenApply_thenPreservesUserOwnedFacts() {
        applier.apply(List.of(), List.of(), identities);

        verify(factMaintenanceService).reconcileManagedPostReactions(
                Set.of(42L, 43L), Set.of(99L), Map.of());
        verify(counterService, never()).unlike(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(counterService, never()).unfav(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void givenCurrentViewsBelowMinimum_whenApply_thenPublishesOnlyMissingDeltaAndPollsUntilVisible() {
        when(counterService.getEffectiveCount("post", "99", "view")).thenReturn(80L, 100L);

        ContentPackReactionApplier.ReactionApplyResult result = applier.apply(
                List.of(), List.of(new SeedViewDefinition("views-one", "post-one", 100L)), identities);

        ArgumentCaptor<CounterEvent> event = ArgumentCaptor.forClass(CounterEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().getMetric()).isEqualTo("view");
        assertThat(event.getValue().getIdx()).isEqualTo(CounterSchema.IDX_VIEW);
        assertThat(event.getValue().getDelta()).isEqualTo(20);
        assertThat(event.getValue().getEventId()).isEqualTo("seed-view-baseline:16:launch-community:99:100");
        assertThat(result.partial()).isFalse();
        verify(counterService, never()).getCounts(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void givenCurrentViewsAboveMinimum_whenApply_thenNeverDecrementsOrPublishes() {
        when(counterService.getEffectiveCount("post", "99", "view")).thenReturn(120L);

        applier.apply(List.of(), List.of(new SeedViewDefinition("views-one", "post-one", 100L)), identities);

        verify(eventPublisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void givenAggregationNotVisibleWithinBound_whenApply_thenReportsPartial() {
        when(counterService.getEffectiveCount("post", "99", "view")).thenReturn(0L);

        ContentPackReactionApplier.ReactionApplyResult result = applier.apply(
                List.of(), List.of(new SeedViewDefinition("views-one", "post-one", 10L)), identities);

        assertThat(result.partial()).isTrue();
        assertThat(result.pendingViewPostIds()).containsExactly(99L);
    }

    @Test
    void givenNewApplierSeesPendingAggregate_whenRetry_thenDoesNotPublishBaselineTwice() {
        when(counterService.getEffectiveCount("post", "99", "view")).thenReturn(0L, 10L);
        SeedViewDefinition view = new SeedViewDefinition("views-one", "post-one", 10L);

        applier.apply(List.of(), List.of(view), identities);
        new ContentPackReactionApplier(
                counterService, factMaintenanceService, eventPublisher, redisson, 1, Duration.ZERO)
                .apply(List.of(), List.of(view), identities);

        verify(eventPublisher, times(1)).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void givenMultipleViewBaselines_whenApply_thenPublishesAllDeltasBeforePollingVisibility() {
        ResolvedIdentities twoPosts = new ResolvedIdentities(
                identities.namespace(), identities.accountIds(), Map.of("post-one", 99L, "post-two", 100L));
        RLock secondLock = mock(RLock.class);
        when(redisson.getLock("lock:seed-content:view-baseline:post:100")).thenReturn(secondLock);
        try {
            when(secondLock.tryLock(5L, 5L, TimeUnit.SECONDS)).thenReturn(true);
        } catch (InterruptedException exception) {
            throw new AssertionError(exception);
        }
        when(secondLock.isHeldByCurrentThread()).thenReturn(true);
        when(counterService.getEffectiveCount("post", "99", "view")).thenReturn(0L);
        when(counterService.getEffectiveCount("post", "100", "view")).thenReturn(0L);
        List<SeedViewDefinition> views = List.of(
                new SeedViewDefinition("views-one", "post-one", 10L),
                new SeedViewDefinition("views-two", "post-two", 20L));

        applier.apply(List.of(), views, twoPosts);

        verify(eventPublisher, times(2)).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void givenEffectiveCountAlreadyIncludesPendingDelta_whenApply_thenDoesNotPublish() {
        when(counterService.getEffectiveCount("post", "99", "view")).thenReturn(100L);

        applier.apply(List.of(), List.of(new SeedViewDefinition("views-one", "post-one", 100L)), identities);

        verify(eventPublisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void givenEventLostAndNoPendingDelta_whenNewApplierRetries_thenPublishesAgain() {
        when(counterService.getEffectiveCount("post", "99", "view")).thenReturn(0L);
        SeedViewDefinition view = new SeedViewDefinition("views-one", "post-one", 10L);

        applier.apply(List.of(), List.of(view), identities);
        new ContentPackReactionApplier(
                counterService, factMaintenanceService, eventPublisher, redisson, 1, Duration.ZERO)
                .apply(List.of(), List.of(view), identities);

        ArgumentCaptor<CounterEvent> retried = ArgumentCaptor.forClass(CounterEvent.class);
        verify(eventPublisher, times(2)).publish(retried.capture());
        assertThat(retried.getAllValues()).extracting(CounterEvent::getEventId)
                .containsOnly("seed-view-baseline:16:launch-community:99:10");
    }

    @Test
    void givenConcurrentAppliers_whenSameBaseline_thenOnlyOnePublishes() throws Exception {
        ReentrantLock actual = new ReentrantLock();
        when(viewLock.tryLock(5L, 5L, TimeUnit.SECONDS))
                .thenAnswer(ignored -> actual.tryLock(5L, TimeUnit.SECONDS));
        when(viewLock.isHeldByCurrentThread()).thenAnswer(ignored -> actual.isHeldByCurrentThread());
        doAnswer(ignored -> { actual.unlock(); return null; }).when(viewLock).unlock();
        AtomicLong effective = new AtomicLong();
        when(counterService.getEffectiveCount("post", "99", "view")).thenAnswer(ignored -> effective.get());
        ScheduledExecutorService aggregation = Executors.newSingleThreadScheduledExecutor();
        doAnswer(invocation -> {
            aggregation.schedule(() -> effective.set(100L), 20L, TimeUnit.MILLISECONDS);
            return null;
        }).when(eventPublisher).publish(org.mockito.ArgumentMatchers.any());
        ContentPackReactionApplier firstApplier = new ContentPackReactionApplier(
                counterService, factMaintenanceService, eventPublisher, redisson, 10, Duration.ofMillis(5));
        ContentPackReactionApplier second = new ContentPackReactionApplier(
                counterService, factMaintenanceService, eventPublisher, redisson, 10, Duration.ofMillis(5));
        SeedViewDefinition view = new SeedViewDefinition("views-one", "post-one", 100L);

        try (aggregation; var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> firstApplier.apply(List.of(), List.of(view), identities));
            var other = executor.submit(() -> second.apply(List.of(), List.of(view), identities));
            first.get(5, TimeUnit.SECONDS);
            other.get(5, TimeUnit.SECONDS);
        }

        verify(eventPublisher, times(1)).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void givenTargetAboveInt32WithNonzeroCurrent_whenApply_thenRejectsBeforeLockOrPublish() {
        SeedViewDefinition overflow = new SeedViewDefinition(
                "views-overflow", "post-one", (long) Integer.MAX_VALUE + 1L);

        assertThatThrownBy(() -> applier.apply(List.of(), List.of(overflow), identities))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Int32");

        verify(redisson, never()).getLock(org.mockito.ArgumentMatchers.anyString());
        verify(eventPublisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void givenUnsupportedReaction_whenApply_thenFailsBeforeMutatingCounters() {
        SeedReactionDefinition reaction = new SeedReactionDefinition("bad", "post-one", "reader", "clap");

        assertThatThrownBy(() -> applier.apply(List.of(reaction), List.of(), identities))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported reaction: clap");

        verify(counterService, never()).like(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        verify(factMaintenanceService, never()).reconcileManagedPostReactions(
                org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyMap());
    }
}
