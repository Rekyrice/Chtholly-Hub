package com.chtholly.agent.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentWsTicketStoreTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private AgentWsTicketStore store;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        store = new AgentWsTicketStore(redis);
    }

    @Test
    void createStoresUserIdWithTtl() {
        doNothing().when(valueOps).set(anyString(), eq("42"), eq(AgentWsTicketStore.TICKET_TTL));

        String ticket = store.create(42L);

        assertThat(ticket).isNotBlank();
        verify(valueOps).set(eq("agent:ws-ticket:" + ticket), eq("42"), eq(AgentWsTicketStore.TICKET_TTL));
    }

    @Test
    void consumeReturnsUserIdAndDeletesTicket() {
        when(valueOps.getAndDelete("agent:ws-ticket:abc")).thenReturn("7");

        assertThat(store.consume("abc")).isEqualTo(7L);
    }

    @Test
    void consumeReturnsNullForMissingTicket() {
        when(valueOps.getAndDelete(anyString())).thenReturn(null);
        assertThat(store.consume("missing")).isNull();
    }
}
