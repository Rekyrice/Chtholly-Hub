package com.chtholly.agent.ws;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final ScheduledExecutorService scheduler;

    private final Map<String, SessionHeartbeat> sessions = new ConcurrentHashMap<>();

    /** Creates the production heartbeat scheduler. */
    public AgentWebSocketHeartbeat() {
        this(newScheduler());
    }

    AgentWebSocketHeartbeat(ScheduledExecutorService scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    void start(WebSocketSession session) {
        SessionHeartbeat hb = new SessionHeartbeat(session);
        SessionHeartbeat previous = sessions.put(session.getId(), hb);
        if (previous != null) {
            previous.stop();
        }
        try {
            hb.schedulePing();
        } catch (RuntimeException exception) {
            hb.stop();
            throw exception;
        }
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

    /** Stops all heartbeat work during application shutdown. */
    @PreDestroy
    public void shutdown() {
        for (SessionHeartbeat heartbeat : List.copyOf(sessions.values())) {
            heartbeat.stop();
        }
        sessions.clear();
        scheduler.shutdownNow();
    }

    private final class SessionHeartbeat {
        private final WebSocketSession session;
        private final AtomicBoolean awaitingPong = new AtomicBoolean(false);
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> pingFuture;
        private volatile ScheduledFuture<?> timeoutFuture;

        private SessionHeartbeat(WebSocketSession session) {
            this.session = session;
        }

        private void schedulePing() {
            ScheduledFuture<?> scheduled = scheduler.scheduleAtFixedRate(
                    this::sendPing,
                    PING_INTERVAL_MS,
                    PING_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
            pingFuture = scheduled;
            if (stopped.get()) {
                scheduled.cancel(false);
            }
        }

        private void sendPing() {
            if (stopped.get()) {
                return;
            }
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
                closeSession("heartbeat ping failed");
            }
        }

        private void recordPong() {
            if (stopped.get()) {
                return;
            }
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
            stopped.set(true);
            if (pingFuture != null) {
                pingFuture.cancel(false);
            }
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
            sessions.remove(session.getId(), this);
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable, "agent-ws-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
