package com.chtholly.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 LLM 超时控制模式（与 ChthollyAgent.callLlm 相同语义）。 */
class AgentLlmTimeoutTest {

    @Test
    void futureGetTimesOutAndCancels() {
        CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });

        assertThatThrownBy(() -> slow.get(1, TimeUnit.SECONDS))
                .isInstanceOf(TimeoutException.class);

        slow.cancel(true);
        assertThat(slow.isCancelled() || slow.isDone()).isTrue();
    }
}
