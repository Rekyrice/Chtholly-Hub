package com.chtholly.agent.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.UUID;

/** Agent WebSocket 握手 ticket：短生命周期、一次性、存 Redis。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWsTicketStore {

    private static final String KEY_PREFIX = "agent:ws-ticket:";
    static final Duration TICKET_TTL = Duration.ofSeconds(60);
    public static final int TICKET_TTL_SECONDS = (int) TICKET_TTL.toSeconds();

    private final StringRedisTemplate redis;

    /** 为已认证用户签发 ticket。 */
    public String create(long userId) {
        String ticket = UUID.randomUUID().toString();
        redis.opsForValue().set(key(ticket), String.valueOf(userId), TICKET_TTL);
        return ticket;
    }

    /** 校验并消费 ticket，返回 userId；无效或已过期返回 null。 */
    public Long consume(String ticket) {
        if (!StringUtils.hasText(ticket)) {
            return null;
        }
        String value = redis.opsForValue().getAndDelete(key(ticket.trim()));
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String key(String ticket) {
        return KEY_PREFIX + ticket;
    }
}
