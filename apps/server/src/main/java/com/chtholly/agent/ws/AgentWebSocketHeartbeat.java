package com.chtholly.agent.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** WebSocket Ping/Pong 心跳：30 秒间隔，10 秒内无 Pong 则断开。 */
@Slf4j
@Component
public class AgentWebSocketHeartbeat {

    static final long PING_INTERVAL_MS = 30_000L;
    static final long PONG_TIMEOUT_MS = 10_000L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-ws-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, SessionHeartbeat> sessions = new ConcurrentHashMap<>();

    void start(WebSocketSession session) {
        SessionHeartbeat hb = new SessionHeartbeat(session);
        SessionHeartbeat previous = sessions.put(session.getId(), hb);
        if (previous != null) {
            previous.stop();
        }
        hb.schedulePing();
    }

    void recordPong(String sessionId) {
        SessionHeartbeat hb = sessions.get(sessionId);
        if (hb != null) {
            hb.recordPong();
        }
    }

    void stop(String sessionId) {
        SessionHeartbeat hb = sessions.remove(sessionId);
        if (hb != null) {
            hb.stop();
        }
    }

    private final class SessionHeartbeat {
        private final WebSocketSession session;
        private final AtomicBoolean awaitingPong = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> pingFuture;
        private volatile ScheduledFuture<?> timeoutFuture;

        private SessionHeartbeat(WebSocketSession session) {
            this.session = session;
        }

        private void schedulePing() {
            pingFuture = scheduler.scheduleAtFixedRate(this::sendPing, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        private void sendPing() {
            if (!session.isOpen()) {
                stop();
                return;
            }
            if (awaitingPong.get()) {
                closeSession("heartbeat pong timeout");
                return;
            }
            try {
                awaitingPong.set(true);
                session.sendMessage(new PingMessage(ByteBuffer.wrap(new byte[0])));
                timeoutFuture = scheduler.schedule(() -> {
                    if (awaitingPong.get()) {
                        closeSession("heartbeat pong timeout");
                    }
                }, PONG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.debug("Agent WS ping failed sessionId={}: {}", session.getId(), e.getMessage());
                stop();
            }
        }

        private void recordPong() {
            awaitingPong.set(false);
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        }

        private void closeSession(String reason) {
            stop();
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE.withReason(reason));
                }
            } catch (Exception e) {
                log.debug("Agent WS close after heartbeat failure: {}", e.getMessage());
            }
        }

        private void stop() {
            if (pingFuture != null) {
                pingFuture.cancel(false);
            }
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
            sessions.remove(session.getId(), this);
        }
    }
}
