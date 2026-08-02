package com.chtholly.agent.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Monotonic, cancellable deadline shared by every stage of one agent turn.
 */
public final class AgentTurnBudget {

    private final long startedAtNanos;
    private final long deadlineNanos;
    private final long startedAtEpochMs;
    private final long deadlineEpochMs;
    private final BooleanSupplier cancelled;
    private final LongSupplier nanoClock;

    private AgentTurnBudget(
            long startedAtNanos,
            long deadlineNanos,
            long startedAtEpochMs,
            long deadlineEpochMs,
            BooleanSupplier cancelled,
            LongSupplier nanoClock) {
        this.startedAtNanos = startedAtNanos;
        this.deadlineNanos = deadlineNanos;
        this.startedAtEpochMs = startedAtEpochMs;
        this.deadlineEpochMs = deadlineEpochMs;
        this.cancelled = cancelled;
        this.nanoClock = nanoClock;
    }

    /**
     * Starts a budget using the JVM monotonic clock.
     *
     * @param timeout whole-turn timeout
     * @param cancelled cooperative cancellation signal
     * @return new turn budget
     */
    public static AgentTurnBudget start(Duration timeout, BooleanSupplier cancelled) {
        return start(timeout, cancelled, System::nanoTime);
    }

    static AgentTurnBudget start(
            Duration timeout,
            BooleanSupplier cancelled,
            LongSupplier nanoClock) {
        Objects.requireNonNull(nanoClock, "nanoClock");
        long startedAt = nanoClock.getAsLong();
        Duration safeTimeout = positiveTimeout(timeout);
        long timeoutNanos = safeTimeout.toNanos();
        long startedAtEpochMs = System.currentTimeMillis();
        return new AgentTurnBudget(
                startedAt,
                saturatingAdd(startedAt, timeoutNanos),
                startedAtEpochMs,
                saturatingAdd(startedAtEpochMs, Math.max(1, safeTimeout.toMillis())),
                cancelled == null ? () -> false : cancelled,
                nanoClock);
    }

    /**
     * Returns a view whose deadline is no later than a limit measured from turn start.
     *
     * @param limit maximum duration from the original turn start
     * @return tightened budget sharing the same clock and cancellation signal
     */
    public AgentTurnBudget limitFromStart(Duration limit) {
        Duration safeLimit = positiveTimeout(limit);
        long limitNanos = safeLimit.toNanos();
        long limitedDeadline = Math.min(
                deadlineNanos,
                saturatingAdd(startedAtNanos, limitNanos));
        long limitedEpochDeadline = Math.min(
                deadlineEpochMs,
                saturatingAdd(startedAtEpochMs, Math.max(1, safeLimit.toMillis())));
        return new AgentTurnBudget(
                startedAtNanos,
                limitedDeadline,
                startedAtEpochMs,
                limitedEpochDeadline,
                cancelled,
                nanoClock);
    }

    /**
     * Returns the usable duration for one stage after checking cancellation and expiry.
     *
     * @param stage stable stage identifier used in failure traces
     * @param stageLimit stage-specific upper bound
     * @return smaller of the stage limit and turn remainder
     * @throws UnavailableException when the turn was cancelled or expired
     */
    public Duration remaining(String stage, Duration stageLimit) {
        check(stage);
        long remainingNanos = deadlineNanos - nanoClock.getAsLong();
        if (remainingNanos <= 0) {
            throw unavailable(UnavailableReason.TIMEOUT, stage);
        }
        long stageNanos = positiveTimeout(stageLimit).toNanos();
        return Duration.ofNanos(Math.min(remainingNanos, stageNanos));
    }

    /**
     * Fails immediately if this turn can no longer perform the named stage.
     *
     * @param stage stable stage identifier
     * @throws UnavailableException when cancelled or expired
     */
    public void check(String stage) {
        if (cancelled.getAsBoolean()) {
            throw unavailable(UnavailableReason.CANCELLED, stage);
        }
        if (nanoClock.getAsLong() >= deadlineNanos) {
            throw unavailable(UnavailableReason.TIMEOUT, stage);
        }
    }

    /** @return effective whole-turn duration represented by this view. */
    public Duration totalBudget() {
        return Duration.ofNanos(Math.max(0, deadlineNanos - startedAtNanos));
    }

    /** @return elapsed monotonic duration since this turn started. */
    public Duration elapsed() {
        return Duration.ofNanos(Math.max(0, nanoClock.getAsLong() - startedAtNanos));
    }

    /** @return wall-clock deadline used by Redis-side fenced scripts. */
    public long deadlineEpochMillis() {
        return deadlineEpochMs;
    }

    /** @return whether the cooperative cancellation signal is set. */
    public boolean isCancelled() {
        return cancelled.getAsBoolean();
    }

    /** @return whether the absolute deadline has elapsed. */
    public boolean isExpired() {
        return nanoClock.getAsLong() >= deadlineNanos;
    }

    /** Creates a stable unavailable exception for adapters that detect expiry externally. */
    public static UnavailableException unavailableForStage(
            UnavailableReason reason,
            String stage) {
        return unavailable(reason, stage);
    }

    private static Duration positiveTimeout(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return Duration.ofNanos(1);
        }
        return duration;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static UnavailableException unavailable(UnavailableReason reason, String stage) {
        return new UnavailableException(reason, stage == null || stage.isBlank() ? "unknown" : stage);
    }

    /** Stable reason why a turn budget cannot execute more work. */
    public enum UnavailableReason {
        TIMEOUT,
        CANCELLED
    }

    /** Raised when a stage attempts to consume an expired or cancelled turn. */
    public static final class UnavailableException extends RuntimeException {
        private final UnavailableReason reason;
        private final String stage;

        private UnavailableException(UnavailableReason reason, String stage) {
            super("Agent turn " + reason.name().toLowerCase() + " at stage " + stage);
            this.reason = reason;
            this.stage = stage;
        }

        public UnavailableReason reason() {
            return reason;
        }

        public String stage() {
            return stage;
        }
    }
}
