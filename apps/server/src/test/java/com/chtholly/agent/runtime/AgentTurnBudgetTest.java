package com.chtholly.agent.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTurnBudgetTest {

    @Test
    void stageReceivesSmallerOfStageLimitAndTurnRemainder() {
        AtomicLong clock = new AtomicLong();
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofSeconds(60), () -> false, clock::get);

        assertThat(budget.remaining("llm", Duration.ofSeconds(30)))
                .isEqualTo(Duration.ofSeconds(30));

        clock.set(Duration.ofSeconds(50).toNanos());
        assertThat(budget.remaining("llm", Duration.ofSeconds(30)))
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void skillLimitIsMeasuredFromOriginalTurnStart() {
        AtomicLong clock = new AtomicLong();
        AgentTurnBudget global = AgentTurnBudget.start(
                Duration.ofSeconds(60), () -> false, clock::get);
        clock.set(Duration.ofSeconds(5).toNanos());

        AgentTurnBudget skill = global.limitFromStart(Duration.ofSeconds(30));

        assertThat(skill.totalBudget()).isEqualTo(Duration.ofSeconds(30));
        assertThat(skill.remaining("retrieval", Duration.ofMinutes(1)))
                .isEqualTo(Duration.ofSeconds(25));
    }

    @Test
    void laterSkillLimitCannotExpandGlobalDeadline() {
        AtomicLong clock = new AtomicLong();
        AgentTurnBudget global = AgentTurnBudget.start(
                Duration.ofSeconds(20), () -> false, clock::get);

        AgentTurnBudget skill = global.limitFromStart(Duration.ofSeconds(45));

        assertThat(skill.totalBudget()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void exposesWallClockDeadlineForRedisFences() {
        long before = System.currentTimeMillis();

        AgentTurnBudget budget = AgentTurnBudget.start(Duration.ofSeconds(20), () -> false);

        long after = System.currentTimeMillis();
        assertThat(budget.deadlineEpochMillis())
                .isBetween(before + 20_000L, after + 20_000L);
    }

    @Test
    void expiredBudgetReportsStageAndTimeoutReason() {
        AtomicLong clock = new AtomicLong();
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofSeconds(10), () -> false, clock::get);
        clock.set(Duration.ofSeconds(10).toNanos());

        assertThatThrownBy(() -> budget.remaining("final_answer", Duration.ofSeconds(30)))
                .isInstanceOf(AgentTurnBudget.UnavailableException.class)
                .satisfies(exception -> {
                    AgentTurnBudget.UnavailableException unavailable =
                            (AgentTurnBudget.UnavailableException) exception;
                    assertThat(unavailable.reason())
                            .isEqualTo(AgentTurnBudget.UnavailableReason.TIMEOUT);
                    assertThat(unavailable.stage()).isEqualTo("final_answer");
                });
    }

    @Test
    void cancellationTakesPriorityOverDeadline() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicLong clock = new AtomicLong();
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofSeconds(10), cancelled::get, clock::get);
        cancelled.set(true);
        clock.set(Duration.ofSeconds(20).toNanos());

        assertThatThrownBy(() -> budget.check("memory_write"))
                .isInstanceOf(AgentTurnBudget.UnavailableException.class)
                .satisfies(exception -> assertThat(
                        ((AgentTurnBudget.UnavailableException) exception).reason())
                        .isEqualTo(AgentTurnBudget.UnavailableReason.CANCELLED));
    }
}
