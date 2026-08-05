package com.chtholly.agent.ws;

import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.AgentEvent;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies per-turn exactly-once lease and delivery resolution. */
class AgentWebSocketActiveTurnTest {

    @Test
    void leaseReleaseRunsOutsideTheTurnMonitorAndSharesOneResult()
            throws Exception {
        AgentTurnCoordinator coordinator = mock(AgentTurnCoordinator.class);
        CountDownLatch releaseStarted = new CountDownLatch(1);
        CountDownLatch allowRelease = new CountDownLatch(1);
        doAnswer(invocation -> {
            releaseStarted.countDown();
            allowRelease.await(2, TimeUnit.SECONDS);
            return true;
        }).when(coordinator).release(7L, "logical-a", "turn-1");
        AgentWebSocketActiveTurn active = activeTurn();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Boolean> owner = executor.submit(() ->
                    active.releaseLeaseOnce(coordinator));
            assertThat(releaseStarted.await(2, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> observer = executor.submit(() ->
                    active.releaseLeaseOnce(coordinator));
            Future<?> cancellation = executor.submit(() ->
                    active.failClientDelivery("CLIENT_DISCONNECTED"));

            cancellation.get(300, TimeUnit.MILLISECONDS);
            assertThat(active.control().isCancelled()).isTrue();
            assertThat(observer.isDone()).isFalse();

            allowRelease.countDown();
            assertThat(owner.get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(observer.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            allowRelease.countDown();
            executor.shutdownNow();
        }

        verify(coordinator, times(1))
                .release(7L, "logical-a", "turn-1");
    }

    @Test
    void repeatedClientFailureCancelsAndResolvesOnlyOnce() {
        AgentWebSocketActiveTurn active = activeTurn();
        AtomicInteger completions = new AtomicInteger();
        active.control().onClientDeliveryResolved(
                completions::incrementAndGet);

        active.failClientDelivery("CLIENT_DELIVERY_FAILED");
        active.failClientDelivery("CLIENT_DISCONNECTED");

        assertThat(active.control().isCancelled()).isTrue();
        assertThat(active.control().clientDeliveryStatus())
                .isEqualTo(AgentTurnControl.ClientDeliveryStatus.FAILED);
        assertThat(active.control().clientDeliveryCode())
                .isEqualTo("CLIENT_DELIVERY_FAILED");
        assertThat(completions).hasValue(1);
    }

    @Test
    void blockedSocketWriteDoesNotBlockConcurrentCancellation()
            throws Exception {
        AgentWebSocketActiveTurn active = activeTurn();
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch allowSendToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> blockedSend = executor.submit(() -> {
                active.acceptEvent(
                        new AgentEvent(
                                "delta",
                                JsonNodeFactory.instance.objectNode()
                                        .put("content", "partial")),
                        ignored -> {
                            sendStarted.countDown();
                            allowSendToFinish.await();
                        });
                return null;
            });
            assertThat(sendStarted.await(2, TimeUnit.SECONDS)).isTrue();

            Future<?> cancellation = executor.submit(() ->
                    active.failClientDelivery("CLIENT_DISCONNECTED"));

            cancellation.get(300, TimeUnit.MILLISECONDS);
            assertThat(active.control().isCancelled()).isTrue();
            assertThat(blockedSend.isDone()).isFalse();
            allowSendToFinish.countDown();
            blockedSend.get(2, TimeUnit.SECONDS);
        } finally {
            allowSendToFinish.countDown();
            executor.shutdownNow();
        }
    }

    private static AgentWebSocketActiveTurn activeTurn() {
        return new AgentWebSocketActiveTurn(
                7L,
                "logical-a",
                AgentTurnControl.create(
                        "request-1",
                        "turn-1",
                        "logical-a",
                        "connection-1",
                        Duration.ofSeconds(30)));
    }
}
