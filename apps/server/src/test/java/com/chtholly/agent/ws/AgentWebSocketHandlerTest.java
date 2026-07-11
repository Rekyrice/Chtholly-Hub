package com.chtholly.agent.ws;

import com.chtholly.agent.ChthollyAgent;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
        handler = new AgentWebSocketHandler(agent, objectMapper, memoryStore, ticketStore, rateLimiter, heartbeat,
                agentMetrics, characterStateService, insightService, cognitiveProvider, notificationService,
                Runnable::run);
    }

    @Test
    void rejectsConnectionWithoutTicket() throws Exception {
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws"));

        handler.afterConnectionEstablished(rawSession);

        verify(rawSession).close(any());
    }

    @Test
    void rateLimitsChatAfterTenMessages() throws Exception {
        when(rawSession.getId()).thenReturn("sess-rate");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t1"));
        when(ticketStore.consume("t1")).thenReturn(99L);

        handler.afterConnectionEstablished(rawSession);

        when(memoryStore.getOrCreateMemory(99L, "sess-chat-a")).thenReturn(memory);
        doNothing().when(agent).run(any(), anyLong(), any(), any(), any(), any());

        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            payloads.add(msg.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());

        for (int i = 0; i < 15; i++) {
            handler.handleTextMessage(rawSession,
                    new TextMessage("{\"type\":\"chat\",\"sessionId\":\"sess-chat-a\",\"message\":\"hi\"}"));
        }

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            long rateLimited = payloads.stream()
                    .filter(p -> p.contains("RATE_LIMITED"))
                    .count();
            assertThat(rateLimited).isGreaterThanOrEqualTo(5);
            verify(agent, atLeast(10)).run(any(), anyLong(), any(), any(), any(), any());
        });
    }

    @Test
    void clearMessageDoesNotCountTowardRateLimit() throws Exception {
        when(rawSession.getId()).thenReturn("sess-clear");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t2"));
        when(ticketStore.consume("t2")).thenReturn(1L);

        handler.afterConnectionEstablished(rawSession);

        when(memoryStore.getOrCreateMemory(1L, "sess-chat-b")).thenReturn(memory);
        doNothing().when(agent).run(any(), anyLong(), any(), any(), any(), any());

        doNothing().when(rawSession).sendMessage(any());

        for (int i = 0; i < 10; i++) {
            handler.handleTextMessage(rawSession,
                    new TextMessage("{\"type\":\"clear\",\"sessionId\":\"sess-chat-b\"}"));
        }
        handler.handleTextMessage(rawSession,
                new TextMessage("{\"type\":\"chat\",\"sessionId\":\"sess-chat-b\",\"message\":\"ok\"}"));

        verify(agent, timeout(2_000)).run(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void recordsCharacterInteractionAfterChatCompletes() throws Exception {
        when(rawSession.getId()).thenReturn("sess-state");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t3"));
        when(ticketStore.consume("t3")).thenReturn(66L);

        handler.afterConnectionEstablished(rawSession);

        when(memoryStore.getOrCreateMemory(66L, "sess-chat-c")).thenReturn(memory);
        doNothing().when(agent).run(any(), anyLong(), any(), any(), any(), any());

        handler.handleTextMessage(rawSession,
                new TextMessage("{\"type\":\"chat\",\"sessionId\":\"sess-chat-c\",\"message\":\"hi\"}"));

        verify(characterStateService).recordInteraction(66L);
        verify(characterStateService).updateEmotion(66L, "hi");
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
        doNothing().when(agent).run(any(), anyLong(), any(), any(), any(), any());

        handler.handleTextMessage(rawSession, new TextMessage("""
                {
                  "type": "chat",
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
                eq("sess-context"),
                contextCaptor.capture(),
                any());
        assertThat(contextCaptor.getValue())
                .contains("页面：/post/frieren-review")
                .contains("标题：《芙莉莲》观后感：时间的重量")
                .contains("来源：post:frieren-review")
                .contains("postSlug：frieren-review")
                .contains("标签：芙莉莲、治愈、日常系");
    }

    @Test
    void reflectsOnConversationAfterConnectionClosed() throws Exception {
        when(rawSession.getId()).thenReturn("sess-reflect");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t5"));
        when(ticketStore.consume("t5")).thenReturn(101L);

        handler.afterConnectionEstablished(rawSession);

        when(memoryStore.getOrCreateMemory(101L, "sess-chat-e")).thenReturn(memory);
        doNothing().when(agent).run(any(), anyLong(), any(), any(), any(), any());
        handler.handleTextMessage(rawSession,
                new TextMessage("{\"type\":\"chat\",\"sessionId\":\"sess-chat-e\",\"message\":\"hi\"}"));
        verify(agent).run(any(), anyLong(), any(), any(), any(), any());

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
}
