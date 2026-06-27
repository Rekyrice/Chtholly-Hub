package com.chtholly.agent.ws;

import com.chtholly.agent.ChthollyAgent;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.observability.AgentMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
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

    private AgentSessionRateLimiter rateLimiter;
    private AgentWebSocketHeartbeat heartbeat;
    private AgentWebSocketHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        rateLimiter = new AgentSessionRateLimiter();
        heartbeat = new AgentWebSocketHeartbeat();
        handler = new AgentWebSocketHandler(agent, objectMapper, memoryStore, ticketStore, rateLimiter, heartbeat, agentMetrics);
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

        when(memoryStore.getOrCreateMemory(99L)).thenReturn(memory);
        doNothing().when(agent).run(any(), anyLong(), any(), any(), any());

        List<String> payloads = new ArrayList<>();
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            payloads.add(msg.getPayload());
            return null;
        }).when(rawSession).sendMessage(any());

        for (int i = 0; i < 15; i++) {
            handler.handleTextMessage(rawSession, new TextMessage("{\"type\":\"chat\",\"message\":\"hi\"}"));
        }

        TimeUnit.MILLISECONDS.sleep(300);

        long rateLimited = payloads.stream()
                .filter(p -> p.contains("RATE_LIMITED"))
                .count();
        assertThat(rateLimited).isGreaterThanOrEqualTo(5);
        verify(agent, atLeast(10)).run(any(), anyLong(), any(), any(), any());
    }

    @Test
    void clearMessageDoesNotCountTowardRateLimit() throws Exception {
        when(rawSession.getId()).thenReturn("sess-clear");
        when(rawSession.getUri()).thenReturn(URI.create("ws://localhost/api/v1/agent/ws?ticket=t2"));
        when(ticketStore.consume("t2")).thenReturn(1L);

        handler.afterConnectionEstablished(rawSession);

        when(memoryStore.getOrCreateMemory(1L)).thenReturn(memory);
        doNothing().when(agent).run(any(), anyLong(), any(), any(), any());

        doNothing().when(rawSession).sendMessage(any());

        for (int i = 0; i < 10; i++) {
            handler.handleTextMessage(rawSession, new TextMessage("{\"type\":\"clear\"}"));
        }
        handler.handleTextMessage(rawSession, new TextMessage("{\"type\":\"chat\",\"message\":\"ok\"}"));

        TimeUnit.MILLISECONDS.sleep(200);
        verify(agent).run(any(), anyLong(), any(), any(), any());
    }
}
