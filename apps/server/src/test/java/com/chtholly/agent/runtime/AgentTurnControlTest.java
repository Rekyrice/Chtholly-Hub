package com.chtholly.agent.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTurnControlTest {

    @Test
    void websocketTurnPublishesOneTerminalDeliveryOutcome() {
        AgentTurnControl control = AgentTurnControl.create(
                "request-1", "turn-1", "session-1", "connection-1", Duration.ofSeconds(30));

        control.completeClientDelivery(true, "final", null);
        control.completeClientDelivery(false, "error", "late-failure");

        assertThat(control.awaitClientDelivery(Duration.ZERO)).isTrue();
        assertThat(control.clientDeliveryStatus())
                .isEqualTo(AgentTurnControl.ClientDeliveryStatus.DELIVERED);
        assertThat(control.clientTerminalType()).isEqualTo("final");
        assertThat(control.clientDeliveryCode()).isBlank();
    }

    @Test
    void standaloneTurnDoesNotWaitForAWebsocketTerminal() {
        AgentTurnControl control = AgentTurnControl.standalone("session-1", Duration.ofSeconds(30));

        assertThat(control.awaitClientDelivery(Duration.ofSeconds(1))).isTrue();
        assertThat(control.clientDeliveryStatus())
                .isEqualTo(AgentTurnControl.ClientDeliveryStatus.NOT_APPLICABLE);
    }

    @Test
    void completionListenerRunsOnceAfterTransportResolution() {
        AgentTurnControl control = AgentTurnControl.create(
                "request-1", "turn-1", "session-1", "connection-1", Duration.ofSeconds(30));
        AtomicInteger callbacks = new AtomicInteger();

        control.onClientDeliveryResolved(callbacks::incrementAndGet);
        assertThat(callbacks).hasValue(0);

        control.completeClientDelivery(false, "final", "CLIENT_DELIVERY_FAILED");
        control.completeClientDelivery(true, "final", null);

        assertThat(callbacks).hasValue(1);
    }

    @Test
    void lateCompletionListenerRunsImmediately() {
        AgentTurnControl control = AgentTurnControl.create(
                "request-1", "turn-1", "session-1", "connection-1", Duration.ofSeconds(30));
        control.completeClientDelivery(true, "final", null);
        AtomicInteger callbacks = new AtomicInteger();

        control.onClientDeliveryResolved(callbacks::incrementAndGet);

        assertThat(callbacks).hasValue(1);
    }
}
