package com.chtholly.agent.ws;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTurnCoordinatorTest {

    @Test
    void sameSessionAllowsOnlyOneActiveTurn() {
        AgentTurnCoordinator coordinator = AgentTurnCoordinator.inMemory();

        AgentTurnCoordinator.AcquireResult first = coordinator.acquire(
                7L, "sess-demo", "request-1", "turn-1", Duration.ofSeconds(30));
        AgentTurnCoordinator.AcquireResult second = coordinator.acquire(
                7L, "sess-demo", "request-2", "turn-2", Duration.ofSeconds(30));

        assertThat(first.status()).isEqualTo(AgentTurnCoordinator.AcquireStatus.ACQUIRED);
        assertThat(first.turnId()).isEqualTo("turn-1");
        assertThat(second.status()).isEqualTo(AgentTurnCoordinator.AcquireStatus.TURN_IN_PROGRESS);
        assertThat(second.turnId()).isEqualTo("turn-1");
    }

    @Test
    void duplicateRequestReturnsOriginalTurnWithoutRunningAgain() {
        AgentTurnCoordinator coordinator = AgentTurnCoordinator.inMemory();

        coordinator.acquire(7L, "sess-demo", "request-1", "turn-1", Duration.ofSeconds(30));
        AgentTurnCoordinator.AcquireResult duplicate = coordinator.acquire(
                7L, "sess-demo", "request-1", "turn-2", Duration.ofSeconds(30));

        assertThat(duplicate.status()).isEqualTo(AgentTurnCoordinator.AcquireStatus.DUPLICATE_REQUEST);
        assertThat(duplicate.turnId()).isEqualTo("turn-1");
    }

    @Test
    void onlyOwnerCanReleaseActiveTurn() {
        AgentTurnCoordinator coordinator = AgentTurnCoordinator.inMemory();
        coordinator.acquire(7L, "sess-demo", "request-1", "turn-1", Duration.ofSeconds(30));

        assertThat(coordinator.release(7L, "sess-demo", "turn-other")).isFalse();
        assertThat(coordinator.acquire(
                7L, "sess-demo", "request-2", "turn-2", Duration.ofSeconds(30)).status())
                .isEqualTo(AgentTurnCoordinator.AcquireStatus.TURN_IN_PROGRESS);

        assertThat(coordinator.release(7L, "sess-demo", "turn-1")).isTrue();
        assertThat(coordinator.acquire(
                7L, "sess-demo", "request-2", "turn-2", Duration.ofSeconds(30)).status())
                .isEqualTo(AgentTurnCoordinator.AcquireStatus.ACQUIRED);
    }

    @Test
    void differentUsersAndSessionsDoNotBlockEachOther() {
        AgentTurnCoordinator coordinator = AgentTurnCoordinator.inMemory();

        assertThat(coordinator.acquire(
                7L, "sess-a", "request-1", "turn-1", Duration.ofSeconds(30)).status())
                .isEqualTo(AgentTurnCoordinator.AcquireStatus.ACQUIRED);
        assertThat(coordinator.acquire(
                7L, "sess-b", "request-2", "turn-2", Duration.ofSeconds(30)).status())
                .isEqualTo(AgentTurnCoordinator.AcquireStatus.ACQUIRED);
        assertThat(coordinator.acquire(
                8L, "sess-a", "request-3", "turn-3", Duration.ofSeconds(30)).status())
                .isEqualTo(AgentTurnCoordinator.AcquireStatus.ACQUIRED);
    }
}
