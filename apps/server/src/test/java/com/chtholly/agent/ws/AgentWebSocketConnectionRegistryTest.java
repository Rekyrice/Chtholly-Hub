package com.chtholly.agent.ws;

import com.chtholly.agent.runtime.AgentTurnControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies atomic connection ownership without parallel session maps. */
class AgentWebSocketConnectionRegistryTest {

    @Test
    void closeAtomicallySealsTasksTurnsAndLogicalSessions() {
        AgentWebSocketConnectionRegistry registry =
                new AgentWebSocketConnectionRegistry();
        WebSocketSession raw = session("connection-1");
        WebSocketSession safe = session("connection-1");
        registry.open(raw, safe, 7L, "correlation-1", 100L);
        FutureTask<Void> task = new FutureTask<>(() -> null);
        AgentWebSocketActiveTurn turn = activeTurn("turn-1");

        assertThat(registry.trackTaskIfOpen("connection-1", task)).isTrue();
        assertThat(registry.registerActiveTurnIfOpen(
                "connection-1", turn)).isTrue();
        assertThat(registry.rememberLogicalSessionIfOpen(
                "connection-1", "logical-a")).isTrue();

        AgentWebSocketConnectionRegistry.CloseSnapshot snapshot =
                registry.close("connection-1");

        assertThat(snapshot.tasks()).containsExactly(task);
        assertThat(snapshot.activeTurns()).containsExactly(turn);
        assertThat(snapshot.logicalSessionIds()).containsExactly("logical-a");
        assertThat(registry.trackTaskIfOpen(
                "connection-1", new FutureTask<>(() -> null))).isFalse();
        assertThat(registry.registerActiveTurnIfOpen(
                "connection-1", activeTurn("turn-2"))).isFalse();
        assertThat(registry.findOpen("connection-1")).isEmpty();
    }

    @Test
    void rejectedExecutorCannotLeaveATrackedTaskBehind() {
        AgentWebSocketConnectionRegistry registry =
                new AgentWebSocketConnectionRegistry();
        WebSocketSession raw = session("connection-rejected");
        when(raw.isOpen()).thenReturn(true);
        List<String> payloads = new CopyOnWriteArrayList<>();
        try {
            doAnswer(invocation -> {
                TextMessage message = invocation.getArgument(0);
                payloads.add(message.getPayload());
                return null;
            }).when(raw).sendMessage(org.mockito.ArgumentMatchers.any());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        registry.open(raw, raw, 7L, "correlation-2", 100L);
        AgentWebSocketTaskExecutor rejectingExecutor =
                new AgentWebSocketTaskExecutor(command -> {
                    throw new java.util.concurrent.RejectedExecutionException(
                            "executor stopped");
                });
        AgentWebSocketProtocolDispatcher dispatcher =
                new AgentWebSocketProtocolDispatcher(
                        new AgentWebSocketProtocolCodec(new ObjectMapper()),
                        new AgentSessionRateLimiter(),
                        registry,
                        mock(AgentWebSocketTurnSubmissionService.class),
                        rejectingExecutor);

        dispatcher.dispatch(raw, """
                {"type":"chat","requestId":"request-rejected"}
                """);

        assertThat(registry.trackedTaskCount("connection-rejected"))
                .isZero();
        assertThat(payloads).hasSize(1);
        try {
            var envelope = new ObjectMapper().readTree(payloads.getFirst());
            assertThat(envelope.path("type").asText())
                    .isEqualTo("rejected");
            assertThat(envelope.path("requestId").asText())
                    .isEqualTo("request-rejected");
            assertThat(envelope.path("data").path("code").asText())
                    .isEqualTo("EXECUTOR_UNAVAILABLE");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void rejectedExecutorClosesConnectionWhenRejectionCannotBeSent()
            throws Exception {
        AgentWebSocketConnectionRegistry registry =
                new AgentWebSocketConnectionRegistry();
        WebSocketSession raw = session("connection-rejected-send");
        when(raw.isOpen()).thenReturn(true);
        doThrow(new IOException("socket unavailable"))
                .when(raw).sendMessage(org.mockito.ArgumentMatchers.any());
        registry.open(raw, raw, 7L, "correlation-3", 100L);
        AgentWebSocketTaskExecutor rejectingExecutor =
                new AgentWebSocketTaskExecutor(command -> {
                    throw new java.util.concurrent.RejectedExecutionException(
                            "executor stopped");
                });
        AgentWebSocketProtocolDispatcher dispatcher =
                new AgentWebSocketProtocolDispatcher(
                        new AgentWebSocketProtocolCodec(new ObjectMapper()),
                        new AgentSessionRateLimiter(),
                        registry,
                        mock(AgentWebSocketTurnSubmissionService.class),
                        rejectingExecutor);

        dispatcher.dispatch(raw, """
                {"type":"chat","requestId":"request-rejected-send"}
                """);

        assertThat(registry.trackedTaskCount("connection-rejected-send"))
                .isZero();
        verify(raw).close(org.mockito.ArgumentMatchers.any());
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    private static AgentWebSocketActiveTurn activeTurn(String turnId) {
        return new AgentWebSocketActiveTurn(
                7L,
                "logical-a",
                AgentTurnControl.create(
                        "request-1",
                        turnId,
                        "logical-a",
                        "connection-1",
                        Duration.ofSeconds(30)));
    }
}
