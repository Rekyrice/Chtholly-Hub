package com.chtholly.agent.ws;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.ChthollyAgent;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.cognitive.CognitiveEngine;
import com.chtholly.agent.learning.InsightService;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.common.tracing.CorrelationIdSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentWebSocketHandlerTest {

    @Mock
    private ChthollyAgent agent;
    @Mock
    private AgentMemoryStore memoryStore;
    @Mock
    private AgentConversationMemory memory;
    @Mock
    private AgentWsTicketStore ticketStore;
    @Mock
    private WebSocketSession rawSession;
    @Mock
    private AgentMetrics agentMetrics;
    @Mock
    private CharacterStateService characterStateService;
    @Mock
    private InsightService insightService;
    @Mock
    private CognitiveEngine cognitiveEngine;
    @Mock
    private NotificationService notificationService;

    private AgentSessionRateLimiter rateLimiter;
    private AgentWebSocketHeartbeat heartbeat;
    private AgentWebSocketHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        rateLimiter = new AgentSessionRateLimiter();
        heartbeat = new AgentWebSocketHeartbeat();
        // 不用 mock ObjectProvider：CI 上 mock stub 可能不生效
        ObjectProvider<CognitiveEngine> cognitiveProvider = new ObjectProvider<>() {
            @Override
            public CognitiveEngine getObject() throws BeansException {
                return cognitiveEngine;
            }

            @Override
            public CognitiveEngine getObject(Object... args) throws BeansException {
                return cognitiveEngine;
            }

            @Override
            public CognitiveEngine getIfAvailable() throws BeansException {
                return cognitiveEngine;
            }

            @Override
            public CognitiveEngine getIfUnique() throws BeansException {
                return cognitiveEngine;
            }
        };
        StaticListableBeanFactory extensionFactory = new StaticListableBeanFactory();
        extensionFactory.addBean("insightService", insightService);
        extensionFactory.addBean("notificationService", notificationService);
        handler = new AgentWebSocketHandler(agent, objectMapper, memoryStore, ticketStore, rateLimiter, heartbeat,
                agentMetrics, characterStateService,
                extensionFactory.getBeanProvider(InsightService.class), cognitiveProvider,
                extensionFactory.getBeanProvider(NotificationService.class),
                Runnable::run);
    }

    @Test
    void rejectsConnectionWithoutTicket() throws Exception {
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws"));

        handler.afterConnectionEstablished(rawSession);

        verify(rawSession).close(any());
    }

    @Test
    void rejectsInvalidChatBeforeTurnAcceptanceWithRequestLevelEvent() throws Exception {
        when(rawSession.getId()).thenReturn("sess-invalid-request");
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=invalid-request"));
        when(ticketStore.consume("invalid-request")).thenReturn(98L);
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            TextMessage sent = invocation.getArgument(0);
            payloads.add(sent.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());

        handler.afterConnectionEstablished(rawSession);
        handler.handleTextMessage(rawSession, new TextMessage(
                chatPayload("sess-chat-invalid", "hello", "request id with spaces")));

        assertThat(payloads).hasSize(1);
        var rejected = objectMapper.readTree(payloads.getFirst());
        assertThat(rejected.path("type").asText()).isEqualTo("rejected");
        assertThat(rejected.path("requestId").asText()).isEqualTo("request id with spaces");
        assertThat(rejected.has("turnId")).isFalse();
        assertThat(rejected.path("data").path("code").asText()).isEqualTo("INVALID_REQUEST_ID");
        verifyNoInteractions(agent);
    }

    @Test
    void rateLimitsChatAfterTenMessages() throws Exception {
        when(rawSession.getId()).thenReturn("sess-rate");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t1"));
        when(ticketStore.consume("t1")).thenReturn(99L);

        handler.afterConnectionEstablished(rawSession);

        when(memoryStore.getOrCreateMemory(99L, "sess-chat-a")).thenReturn(memory);
        doNothing().when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            payloads.add(msg.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());

        for (int i = 0; i < 15; i++) {
            handler.handleTextMessage(rawSession,
                    new TextMessage(chatPayload("sess-chat-a", "hi", "req-rate-" + i)));
        }

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            long rateLimited = payloads.stream()
                    .filter(p -> p.contains("RATE_LIMITED"))
                    .count();
            assertThat(rateLimited).isGreaterThanOrEqualTo(5);
            verify(agent, atLeast(10)).run(
                    any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());
        });
    }

    @Test
    void clearMessageDoesNotCountTowardRateLimit() throws Exception {
        when(rawSession.getId()).thenReturn("sess-clear");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t2"));
        when(ticketStore.consume("t2")).thenReturn(1L);

        handler.afterConnectionEstablished(rawSession);

        when(memoryStore.getOrCreateMemory(1L, "sess-chat-b")).thenReturn(memory);
        doNothing().when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        doNothing().when(rawSession).sendMessage(any());

        for (int i = 0; i < 10; i++) {
            handler.handleTextMessage(rawSession,
                    new TextMessage("{\"type\":\"clear\",\"sessionId\":\"sess-chat-b\"}"));
        }
        handler.handleTextMessage(rawSession,
                new TextMessage(chatPayload("sess-chat-b", "ok", "req-clear-ok")));

        verify(agent, timeout(2_000)).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());
    }

    @Test
    void clearAcknowledgementEchoesItsRequestIdWithoutCreatingATurn() throws Exception {
        when(rawSession.getId()).thenReturn("sess-clear-correlation");
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=clear-correlation"));
        when(ticketStore.consume("clear-correlation")).thenReturn(10L);
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            TextMessage sent = invocation.getArgument(0);
            payloads.add(sent.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());
        handler.afterConnectionEstablished(rawSession);

        handler.handleTextMessage(rawSession, new TextMessage(
                "{\"type\":\"clear\",\"sessionId\":\"sess-chat-clear\","
                        + "\"requestId\":\"clear-request-1\"}"));

        assertThat(payloads).hasSize(1);
        var cleared = objectMapper.readTree(payloads.getFirst());
        assertThat(cleared.path("type").asText()).isEqualTo("cleared");
        assertThat(cleared.path("requestId").asText()).isEqualTo("clear-request-1");
        assertThat(cleared.has("turnId")).isFalse();
        verify(memoryStore).clearMemory(10L, "sess-chat-clear");
        verifyNoInteractions(agent);
    }

    @Test
    void recordsCharacterInteractionAfterChatCompletes() throws Exception {
        when(rawSession.getId()).thenReturn("sess-state");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t3"));
        when(ticketStore.consume("t3")).thenReturn(66L);

        handler.afterConnectionEstablished(rawSession);

        when(memoryStore.getOrCreateMemory(66L, "sess-chat-c")).thenReturn(memory);
        doNothing().when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        handler.handleTextMessage(rawSession,
                new TextMessage(chatPayload("sess-chat-c", "hi", "req-state")));

        verify(characterStateService).recordInteraction(66L);
        verify(characterStateService).updateEmotion(eq(66L), eq("hi"), any(java.time.Instant.class));
    }

    @Test
    void sameConnectionTwoChatTurnsUseDistinctCorrelationIds() throws Exception {
        when(rawSession.getId()).thenReturn("sess-two-turns");
        when(rawSession.getAttributes()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=two-turns"));
        when(ticketStore.consume("two-turns")).thenReturn(67L);
        when(memoryStore.getOrCreateMemory(67L, "sess-chat-two")).thenReturn(memory);
        List<String> correlationIds = new CopyOnWriteArrayList<>();
        List<String> traceCorrelationIds = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            correlationIds.add(MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID));
            traceCorrelationIds.add(new AgentExecutionTrace(67L, "sess-chat-two", 3).getCorrelationId());
            return null;
        }).when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        handler.afterConnectionEstablished(rawSession);
        handler.handleTextMessage(rawSession, new TextMessage(
                chatPayload("sess-chat-two", "first", "req-first")));
        handler.handleTextMessage(rawSession, new TextMessage(
                chatPayload("sess-chat-two", "second", "req-second")));

        assertThat(correlationIds)
                .hasSize(2)
                .allMatch(id -> id != null && !id.isBlank());
        assertThat(correlationIds.get(0)).isNotEqualTo(correlationIds.get(1));
        assertThat(traceCorrelationIds).containsExactly(
                correlationIds.get(0).replace("-", ""),
                correlationIds.get(1).replace("-", ""));
        assertThat(traceCorrelationIds.get(0)).isNotEqualTo(traceCorrelationIds.get(1));
    }

    @Test
    void acceptedAndTurnEventsCarryCanonicalRequestAndTurnIds() throws Exception {
        when(rawSession.getId()).thenReturn("sess-protocol");
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=protocol"));
        when(rawSession.isOpen()).thenReturn(true);
        when(ticketStore.consume("protocol")).thenReturn(68L);
        when(memoryStore.getOrCreateMemory(68L, "sess-chat-protocol")).thenReturn(memory);
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            TextMessage sent = invocation.getArgument(0);
            payloads.add(sent.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AgentEvent> sink = invocation.getArgument(6);
            sink.accept(new AgentEvent(
                    "delta", objectMapper.createObjectNode().put("content", "answer")));
            return null;
        }).when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        handler.afterConnectionEstablished(rawSession);
        handler.handleTextMessage(rawSession, new TextMessage(
                chatPayload("sess-chat-protocol", "hello", "request-protocol")));

        assertThat(payloads).hasSizeGreaterThanOrEqualTo(2);
        var accepted = objectMapper.readTree(payloads.get(0));
        var delta = objectMapper.readTree(payloads.get(1));
        assertThat(accepted.path("type").asText()).isEqualTo("accepted");
        assertThat(accepted.path("requestId").asText()).isEqualTo("request-protocol");
        assertThat(accepted.path("turnId").asText()).isNotBlank();
        assertThat(delta.path("type").asText()).isEqualTo("delta");
        assertThat(delta.path("requestId").asText()).isEqualTo("request-protocol");
        assertThat(delta.path("turnId").asText()).isEqualTo(accepted.path("turnId").asText());
    }

    @Test
    void failureAfterAcceptanceRemainsBoundToTheAcceptedTurn() throws Exception {
        when(rawSession.getId()).thenReturn("sess-turn-failure");
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=turn-failure"));
        when(rawSession.isOpen()).thenReturn(true);
        when(ticketStore.consume("turn-failure")).thenReturn(70L);
        when(memoryStore.getOrCreateMemory(70L, "sess-chat-turn-failure")).thenReturn(memory);
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            TextMessage sent = invocation.getArgument(0);
            payloads.add(sent.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());
        doAnswer(invocation -> {
            throw new IllegalStateException("boom");
        }).when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        handler.afterConnectionEstablished(rawSession);
        handler.handleTextMessage(rawSession, new TextMessage(
                chatPayload("sess-chat-turn-failure", "hello", "request-turn-failure")));

        assertThat(payloads).hasSize(2);
        var accepted = objectMapper.readTree(payloads.get(0));
        var error = objectMapper.readTree(payloads.get(1));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("requestId").asText()).isEqualTo("request-turn-failure");
        assertThat(error.path("turnId").asText()).isEqualTo(accepted.path("turnId").asText());
        assertThat(error.path("data").path("code").asText()).isEqualTo("TURN_FAILED");
    }

    @Test
    void failureLoadingMemoryAfterAcceptanceRemainsBoundToTheAcceptedTurn() throws Exception {
        when(rawSession.getId()).thenReturn("sess-memory-failure");
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=memory-failure"));
        when(rawSession.isOpen()).thenReturn(true);
        when(ticketStore.consume("memory-failure")).thenReturn(73L);
        when(memoryStore.getOrCreateMemory(73L, "sess-chat-memory-failure"))
                .thenThrow(new IllegalStateException("redis unavailable"));
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            TextMessage sent = invocation.getArgument(0);
            payloads.add(sent.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());

        handler.afterConnectionEstablished(rawSession);
        handler.handleTextMessage(rawSession, new TextMessage(
                chatPayload("sess-chat-memory-failure", "hello", "request-memory-failure")));

        assertThat(payloads).hasSize(2);
        var accepted = objectMapper.readTree(payloads.get(0));
        var error = objectMapper.readTree(payloads.get(1));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("requestId").asText()).isEqualTo("request-memory-failure");
        assertThat(error.path("turnId").asText()).isEqualTo(accepted.path("turnId").asText());
        assertThat(error.path("data").path("code").asText()).isEqualTo("TURN_FAILED");
        verifyNoInteractions(agent);
    }

    @Test
    void terminalEventIsSentOnlyAfterAgentReturnsAndTheLeaseIsReleased() throws Exception {
        ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        AgentTurnCoordinator coordinator = AgentTurnCoordinator.inMemory();
        AgentWebSocketHandler asyncHandler = handler(coordinator, asyncExecutor);
        CountDownLatch terminalProduced = new CountDownLatch(1);
        CountDownLatch allowAgentReturn = new CountDownLatch(1);
        when(rawSession.getId()).thenReturn("sess-terminal-order");
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=terminal-order"));
        when(rawSession.isOpen()).thenReturn(true);
        when(ticketStore.consume("terminal-order")).thenReturn(71L);
        when(memoryStore.getOrCreateMemory(71L, "sess-chat-terminal-order")).thenReturn(memory);
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            TextMessage sent = invocation.getArgument(0);
            payloads.add(sent.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AgentEvent> sink = invocation.getArgument(6);
            sink.accept(new AgentEvent(
                    "delta", objectMapper.createObjectNode().put("content", "answer")));
            sink.accept(new AgentEvent(
                    "final", objectMapper.createObjectNode().put("content", "answer")));
            terminalProduced.countDown();
            allowAgentReturn.await(2, TimeUnit.SECONDS);
            return null;
        }).when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        try {
            asyncHandler.afterConnectionEstablished(rawSession);
            asyncHandler.handleTextMessage(rawSession, new TextMessage(
                    chatPayload("sess-chat-terminal-order", "hello", "request-terminal-order")));

            assertThat(terminalProduced.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(payloads).noneMatch(payload -> payload.contains("\"type\":\"final\""));
            assertThat(coordinator.acquire(
                    71L,
                    "sess-chat-terminal-order",
                    "request-before-return",
                    "turn-before-return",
                    Duration.ofSeconds(30)).status())
                    .isEqualTo(AgentTurnCoordinator.AcquireStatus.TURN_IN_PROGRESS);

            allowAgentReturn.countDown();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(payloads)
                    .anyMatch(payload -> payload.contains("\"type\":\"final\"")));
            assertThat(coordinator.acquire(
                    71L,
                    "sess-chat-terminal-order",
                    "request-after-final",
                    "turn-after-final",
                    Duration.ofSeconds(30)).status())
                    .isEqualTo(AgentTurnCoordinator.AcquireStatus.ACQUIRED);
        } finally {
            allowAgentReturn.countDown();
            asyncExecutor.shutdownNow();
        }
    }

    @Test
    void releaseFailureReplacesFinalWithBoundCoordinationErrorAndClosesConnection() throws Exception {
        AgentTurnCoordinator coordinator = org.mockito.Mockito.mock(AgentTurnCoordinator.class);
        AgentWebSocketHandler releaseFailureHandler = handler(coordinator, Runnable::run);
        when(coordinator.acquire(anyLong(), anyString(), anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> new AgentTurnCoordinator.AcquireResult(
                        AgentTurnCoordinator.AcquireStatus.ACQUIRED,
                        invocation.getArgument(3)));
        when(coordinator.release(anyLong(), anyString(), anyString())).thenReturn(false);
        when(rawSession.getId()).thenReturn("sess-release-failure");
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=release-failure"));
        when(rawSession.isOpen()).thenReturn(true);
        when(ticketStore.consume("release-failure")).thenReturn(74L);
        when(memoryStore.getOrCreateMemory(74L, "sess-chat-release-failure")).thenReturn(memory);
        List<String> payloads = new CopyOnWriteArrayList<>();
        AtomicReference<AgentTurnControl> controlRef = new AtomicReference<>();
        doAnswer(invocation -> {
            TextMessage sent = invocation.getArgument(0);
            payloads.add(sent.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());
        doAnswer(invocation -> {
            controlRef.set(invocation.getArgument(3));
            @SuppressWarnings("unchecked")
            Consumer<AgentEvent> sink = invocation.getArgument(6);
            sink.accept(new AgentEvent("delta", objectMapper.createObjectNode().put("content", "answer")));
            sink.accept(new AgentEvent("final", objectMapper.createObjectNode().put("content", "answer")));
            return null;
        }).when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        releaseFailureHandler.afterConnectionEstablished(rawSession);
        releaseFailureHandler.handleTextMessage(rawSession, new TextMessage(
                chatPayload("sess-chat-release-failure", "hello", "request-release-failure")));

        assertThat(payloads).anyMatch(payload -> payload.contains("TURN_COORDINATION_UNAVAILABLE"));
        assertThat(payloads).noneMatch(payload -> payload.contains("\"type\":\"final\""));
        assertThat(controlRef.get().clientDeliveryStatus())
                .isEqualTo(AgentTurnControl.ClientDeliveryStatus.DELIVERED);
        assertThat(controlRef.get().clientTerminalType()).isEqualTo("error");
        verify(rawSession).close(any());
    }

    @Test
    void terminalDeliveryFailureMarksTraceOutcomeAndClosesConnection() throws Exception {
        when(rawSession.getId()).thenReturn("sess-terminal-failure");
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=terminal-failure"));
        when(rawSession.isOpen()).thenReturn(true);
        when(ticketStore.consume("terminal-failure")).thenReturn(75L);
        when(memoryStore.getOrCreateMemory(75L, "sess-chat-terminal-failure")).thenReturn(memory);
        AtomicReference<AgentTurnControl> controlRef = new AtomicReference<>();
        doAnswer(invocation -> {
            TextMessage sent = invocation.getArgument(0);
            if (sent.getPayload().contains("\"type\":\"final\"")) {
                throw new IllegalStateException("terminal socket write failed");
            }
            return null;
        }).when(rawSession).sendMessage(any());
        doAnswer(invocation -> {
            controlRef.set(invocation.getArgument(3));
            @SuppressWarnings("unchecked")
            Consumer<AgentEvent> sink = invocation.getArgument(6);
            sink.accept(new AgentEvent("delta", objectMapper.createObjectNode().put("content", "answer")));
            sink.accept(new AgentEvent("final", objectMapper.createObjectNode().put("content", "answer")));
            return null;
        }).when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        handler.afterConnectionEstablished(rawSession);
        handler.handleTextMessage(rawSession, new TextMessage(
                chatPayload("sess-chat-terminal-failure", "hello", "request-terminal-failure")));

        assertThat(controlRef.get().clientDeliveryStatus())
                .isEqualTo(AgentTurnControl.ClientDeliveryStatus.FAILED);
        assertThat(controlRef.get().clientDeliveryCode()).isEqualTo("CLIENT_DELIVERY_FAILED");
        verify(agentMetrics).recordError("client_delivery");
        verify(rawSession).close(any());
    }

    @Test
    void eventDeliveryFailureCancelsTheAcceptedTurn() throws Exception {
        when(rawSession.getId()).thenReturn("sess-delivery-failure");
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=delivery-failure"));
        when(rawSession.isOpen()).thenReturn(true);
        when(ticketStore.consume("delivery-failure")).thenReturn(72L);
        when(memoryStore.getOrCreateMemory(72L, "sess-chat-delivery-failure")).thenReturn(memory);
        AtomicReference<AgentTurnControl> controlRef = new AtomicReference<>();
        doAnswer(invocation -> {
            TextMessage sent = invocation.getArgument(0);
            if (sent.getPayload().contains("\"type\":\"delta\"")) {
                throw new IllegalStateException("socket write failed");
            }
            return null;
        }).when(rawSession).sendMessage(any());
        doAnswer(invocation -> {
            AgentTurnControl control = invocation.getArgument(3);
            controlRef.set(control);
            @SuppressWarnings("unchecked")
            Consumer<AgentEvent> sink = invocation.getArgument(6);
            sink.accept(new AgentEvent(
                    "delta", objectMapper.createObjectNode().put("content", "answer")));
            sink.accept(new AgentEvent(
                    "final", objectMapper.createObjectNode().put("content", "answer")));
            return null;
        }).when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        handler.afterConnectionEstablished(rawSession);
        handler.handleTextMessage(rawSession, new TextMessage(
                chatPayload("sess-chat-delivery-failure", "hello", "request-delivery-failure")));

        assertThat(controlRef.get()).isNotNull();
        assertThat(controlRef.get().isCancelled()).isTrue();
    }

    @Test
    void concurrentTurnForSameLogicalSessionIsRejectedAndCloseCancelsOwner() throws Exception {
        ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        AgentTurnCoordinator coordinator = AgentTurnCoordinator.inMemory();
        AgentWebSocketHandler asyncHandler = handler(
                coordinator, asyncExecutor);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        when(rawSession.getId()).thenReturn("sess-concurrent");
        when(rawSession.getUri()).thenReturn(
                URI.create("ws://localhost/api/v1/agent/ws?ticket=concurrent"));
        when(ticketStore.consume("concurrent")).thenReturn(69L);
        when(memoryStore.getOrCreateMemory(69L, "sess-chat-shared")).thenReturn(memory);
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            TextMessage sent = invocation.getArgument(0);
            payloads.add(sent.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());
        doAnswer(invocation -> {
            AgentTurnControl control = invocation.getArgument(3);
            started.countDown();
            while (!control.isCancelled()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            cancelled.countDown();
            return null;
        }).when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        try {
            asyncHandler.afterConnectionEstablished(rawSession);
            asyncHandler.handleTextMessage(rawSession, new TextMessage(
                    chatPayload("sess-chat-shared", "first", "request-first")));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            asyncHandler.handleTextMessage(rawSession, new TextMessage(
                    chatPayload("sess-chat-shared", "second", "request-second")));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(payloads)
                    .anyMatch(payload -> payload.contains("TURN_IN_PROGRESS")));
            var rejected = payloads.stream()
                    .map(payload -> {
                        try {
                            return objectMapper.readTree(payload);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .filter(node -> "rejected".equals(node.path("type").asText()))
                    .findFirst()
                    .orElseThrow();
            assertThat(rejected.path("requestId").asText()).isEqualTo("request-second");
            assertThat(rejected.has("turnId")).isFalse();

            asyncHandler.afterConnectionClosed(
                    rawSession, org.springframework.web.socket.CloseStatus.NORMAL);
            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.acquire(
                    69L,
                    "sess-chat-shared",
                    "request-after-close",
                    "turn-after-close",
                    Duration.ofSeconds(30)).status())
                    .isEqualTo(AgentTurnCoordinator.AcquireStatus.ACQUIRED);
        } finally {
            asyncExecutor.shutdownNow();
        }
    }

    @Test
    void pushesPendingProactiveNotificationsAfterConnectionEstablished() throws Exception {
        when(rawSession.getId()).thenReturn("sess-proactive");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t-pro"));
        when(rawSession.isOpen()).thenReturn(true);
        when(ticketStore.consume("t-pro")).thenReturn(77L);
        when(notificationService.getPendingNotifications(77L)).thenReturn(List.of(
                new Notification(
                        "missing-you",
                        "I kept your seat by the window.",
                        java.time.Instant.parse("2026-07-04T12:00:00Z"),
                        NotificationChannel.FLOATING)
        ));
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            payloads.add(msg.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());

        handler.afterConnectionEstablished(rawSession);

        assertThat(payloads)
                .anySatisfy(payload -> assertThat(payload)
                        .contains("\"type\":\"proactive\"")
                        .contains("I kept your seat by the window."));
        verify(notificationService).registerSession(eq(77L), eq("sess-proactive"), any());
    }

    @Test
    void passesPageContextFromChatPayloadToAgent() throws Exception {
        when(rawSession.getId()).thenReturn("sess-context");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t4"));
        when(ticketStore.consume("t4")).thenReturn(88L);

        handler.afterConnectionEstablished(rawSession);

        when(memoryStore.getOrCreateMemory(88L, "sess-chat-d")).thenReturn(memory);
        doNothing().when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        handler.handleTextMessage(rawSession, new TextMessage("""
                {
                  "type": "chat",
                  "requestId": "req-context",
                  "sessionId": "sess-chat-d",
                  "message": "hi",
                    "context": {
                      "page": "/post/frieren-review",
                      "title": "《芙莉莲》观后感：时间的重量",
                      "source": "post:frieren-review",
                      "postSlug": "frieren-review",
                      "tags": ["芙莉莲", "治愈", "日常系"]
                    }
                  }
                """));

        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(agent).run(
                eq("hi"),
                eq(88L),
                eq(memory),
                any(AgentTurnControl.class),
                contextCaptor.capture(),
                any(),
                any());
        assertThat(contextCaptor.getValue())
                .contains("页面：/post/frieren-review")
                .contains("标题：《芙莉莲》观后感：时间的重量")
                .contains("来源：post:frieren-review")
                .contains("postSlug：frieren-review")
                .contains("标签：芙莉莲、治愈、日常系");
    }

    @Test
    void passesExplicitTaskTypeToSkillAwareAgentBoundary() throws Exception {
        when(rawSession.getId()).thenReturn("sess-skill");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=skill"));
        when(ticketStore.consume("skill")).thenReturn(89L);
        when(memoryStore.getOrCreateMemory(89L, "sess-chat-skill")).thenReturn(memory);
        doNothing().when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        handler.afterConnectionEstablished(rawSession);
        handler.handleTextMessage(rawSession, new TextMessage("""
                {
                  "type": "chat",
                  "requestId": "req-skill",
                  "sessionId": "sess-chat-skill",
                  "message": "解释这个页面",
                  "taskType": "page-explain",
                  "context": {"page": "/post/1"}
                }
                """));

        verify(agent).run(
                eq("解释这个页面"),
                eq(89L),
                eq(memory),
                any(AgentTurnControl.class),
                any(),
                eq("page-explain"),
                any());
    }

    @Test
    void reflectsOnConversationAfterConnectionClosed() throws Exception {
        when(rawSession.getId()).thenReturn("sess-reflect");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t5"));
        when(ticketStore.consume("t5")).thenReturn(101L);

        handler.afterConnectionEstablished(rawSession);

        when(memoryStore.getOrCreateMemory(101L, "sess-chat-e")).thenReturn(memory);
        doNothing().when(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());
        handler.handleTextMessage(rawSession,
                new TextMessage(chatPayload("sess-chat-e", "hi", "req-reflect")));
        verify(agent).run(
                any(), anyLong(), any(), any(AgentTurnControl.class), any(), any(), any());

        List<AgentTurn> turns = List.of(
                AgentTurn.user("角色有哪些？"),
                AgentTurn.assistant("先列主要角色。"),
                AgentTurn.user("声优呢？"),
                AgentTurn.assistant("补充声优。"),
                AgentTurn.user("评分呢？"),
                AgentTurn.assistant("补充评分。")
        );
        when(memoryStore.getTurns(101L, "sess-chat-e")).thenReturn(turns);

        handler.afterConnectionClosed(rawSession, org.springframework.web.socket.CloseStatus.NORMAL);

        verify(insightService).reflectOnConversation(101L, turns);
    }

    @Test
    void triggersCognitiveCycleAfterConnectionClosed() throws Exception {
        when(rawSession.getId()).thenReturn("sess-cognitive");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t6"));
        when(ticketStore.consume("t6")).thenReturn(102L);

        handler.afterConnectionEstablished(rawSession);
        handler.afterConnectionClosed(rawSession, org.springframework.web.socket.CloseStatus.NORMAL);

        verify(cognitiveEngine).triggerIfDue();
    }

    @Test
    void chatLifecycleWorksWhenOptionalLearningAndNotificationExtensionsAreDisabled() {
        StaticListableBeanFactory emptyFactory = new StaticListableBeanFactory();
        AgentWebSocketHandler coreOnlyHandler = new AgentWebSocketHandler(
                agent, objectMapper, memoryStore, ticketStore, rateLimiter, heartbeat,
                agentMetrics, characterStateService,
                emptyFactory.getBeanProvider(InsightService.class),
                emptyFactory.getBeanProvider(CognitiveEngine.class),
                emptyFactory.getBeanProvider(NotificationService.class),
                Runnable::run);
        when(rawSession.getId()).thenReturn("sess-core-only");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=core-only"));
        when(ticketStore.consume("core-only")).thenReturn(201L);
        when(memoryStore.getOrCreateMemory(201L, "chat-core-only")).thenReturn(memory);

        assertDoesNotThrow(() -> {
            coreOnlyHandler.afterConnectionEstablished(rawSession);
            coreOnlyHandler.handleTextMessage(rawSession,
                    new TextMessage(chatPayload("chat-core-only", "hello", "req-core-only")));
            coreOnlyHandler.afterConnectionClosed(rawSession, org.springframework.web.socket.CloseStatus.NORMAL);
        });

        verify(agent).run(
                any(), eq(201L), eq(memory), any(AgentTurnControl.class), any(), any(), any());
        verifyNoInteractions(insightService, notificationService);
    }

    private AgentWebSocketHandler handler(
            AgentTurnCoordinator coordinator,
            java.util.concurrent.Executor executor) {
        StaticListableBeanFactory extensionFactory = new StaticListableBeanFactory();
        extensionFactory.addBean("insightService", insightService);
        extensionFactory.addBean("notificationService", notificationService);
        return new AgentWebSocketHandler(
                agent,
                objectMapper,
                memoryStore,
                ticketStore,
                rateLimiter,
                heartbeat,
                agentMetrics,
                characterStateService,
                extensionFactory.getBeanProvider(InsightService.class),
                new StaticListableBeanFactory().getBeanProvider(CognitiveEngine.class),
                extensionFactory.getBeanProvider(NotificationService.class),
                coordinator,
                new com.chtholly.agent.config.AgentProperties(),
                executor);
    }

    private String chatPayload(String sessionId, String message, String requestId) {
        return "{\"type\":\"chat\",\"requestId\":\"" + requestId
                + "\",\"sessionId\":\"" + sessionId
                + "\",\"message\":\"" + message + "\"}";
    }
}
