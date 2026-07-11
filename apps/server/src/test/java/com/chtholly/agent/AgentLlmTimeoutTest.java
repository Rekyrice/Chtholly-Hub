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
        CompletableFuture<String> pending = new CompletableFuture<>();

        assertThatThrownBy(() -> pending.get(20, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        pending.cancel(true);
        assertThat(pending).isCancelled();
    }
}
