package com.chtholly.agent.ws;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.ChthollyAgent;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Verifies accepted-turn terminal, delivery, and lease ordering. */
class AgentWebSocketAcceptedTurnRunnerTest {

    @Test
    void resolvesUnacceptedTurnWhenConnectionAlreadyClosed() {
        Fixture fixture = new Fixture(false);
        when(fixture.coordinator.release(
                7L, "logical-a", "turn-1")).thenReturn(true);

        fixture.runner.run(
                fixture.connection,
                fixture.request,
                fixture.admission);

        assertThat(fixture.activeTurn.control().clientDeliveryStatus())
                .isEqualTo(AgentTurnControl.ClientDeliveryStatus.FAILED);
        assertThat(fixture.activeTurn.control().clientDeliveryCode())
                .isEqualTo("CLIENT_DISCONNECTED");
        verifyNoInteractions(fixture.agent, fixture.extensions);
        verify(fixture.delivery, never()).sendAccepted(any(), any(), any());
        verify(fixture.coordinator).release(
                7L, "logical-a", "turn-1");
    }

    @Test
    void deliversTerminalBeforeAfterTurnAndLeaseRelease() {
        Fixture fixture = new Fixture(true);
        when(fixture.delivery.sendAccepted(
                fixture.safeSession, "request-1", fixture.activeTurn))
                .thenReturn(true);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AgentEvent> sink = invocation.getArgument(6);
            sink.accept(new AgentEvent(
                    "final",
                    JsonNodeFactory.instance.objectNode()
                            .put("content", "answer")));
            return null;
        }).when(fixture.agent).run(
                any(),
                anyLong(),
                any(),
                any(AgentTurnControl.class),
                any(),
                any(),
                any());
        doAnswer(invocation -> {
            fixture.activeTurn.completeClientDelivery(true, "final", "");
            return null;
        }).when(fixture.delivery).sendTerminal(
                same(fixture.safeSession),
                any(AgentEvent.class),
                eq("request-1"),
                same(fixture.activeTurn));
        when(fixture.coordinator.release(
                7L, "logical-a", "turn-1")).thenReturn(true);

        fixture.runner.run(
                fixture.connection,
                fixture.request,
                fixture.admission);

        InOrder order = inOrder(
                fixture.delivery,
                fixture.extensions,
                fixture.coordinator);
        order.verify(fixture.delivery).sendTerminal(
                same(fixture.safeSession),
                any(AgentEvent.class),
                eq("request-1"),
                same(fixture.activeTurn));
        order.verify(fixture.extensions).afterTurn(7L, "hello");
        order.verify(fixture.coordinator).release(
                7L, "logical-a", "turn-1");
        assertThat(fixture.activeTurn.control().clientDeliveryStatus())
                .isEqualTo(AgentTurnControl.ClientDeliveryStatus.DELIVERED);
    }

    private static final class Fixture {
        private final ChthollyAgent agent = mock(ChthollyAgent.class);
        private final AgentTurnCoordinator coordinator =
                mock(AgentTurnCoordinator.class);
        private final AgentWebSocketConnectionRegistry registry =
                new AgentWebSocketConnectionRegistry();
        private final AgentWebSocketDeliveryService delivery =
                mock(AgentWebSocketDeliveryService.class);
        private final AgentWebSocketExtensionLifecycle extensions =
                mock(AgentWebSocketExtensionLifecycle.class);
        private final WebSocketSession rawSession =
                mock(WebSocketSession.class);
        private final WebSocketSession safeSession =
                mock(WebSocketSession.class);
        private final AgentWebSocketConnectionRegistry.ConnectionContext
                connection;
        private final AgentWebSocketActiveTurn activeTurn;
        private final AgentWebSocketProtocolCodec.ChatRequest request =
                new AgentWebSocketProtocolCodec.ChatRequest(
                        "request-1",
                        "logical-a",
                        "hello",
                        "",
                        "");
        private final AgentWebSocketTurnAdmissionService.Admission admission;
        private final AgentWebSocketAcceptedTurnRunner runner;

        private Fixture(boolean sessionOpen) {
            when(rawSession.getId()).thenReturn("connection-1");
            when(safeSession.isOpen()).thenReturn(sessionOpen);
            connection = registry.open(
                    rawSession,
                    safeSession,
                    7L,
                    "correlation-1",
                    1L);
            activeTurn = new AgentWebSocketActiveTurn(
                    7L,
                    "logical-a",
                    AgentTurnControl.create(
                            "request-1",
                            "turn-1",
                            "logical-a",
                            "connection-1",
                            Duration.ofSeconds(30)));
            assertThat(registry.registerActiveTurnIfOpen(
                    "connection-1", activeTurn)).isTrue();
            admission = new AgentWebSocketTurnAdmissionService.Admission(
                    mock(AgentConversationMemory.class), activeTurn);
            runner = new AgentWebSocketAcceptedTurnRunner(
                    agent,
                    coordinator,
                    registry,
                    delivery,
                    extensions);
        }
    }
}
