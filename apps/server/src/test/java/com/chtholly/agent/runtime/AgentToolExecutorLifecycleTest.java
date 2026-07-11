package com.chtholly.agent.runtime;

import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolExecutorLifecycleTest {

    @Test
    void ownedExecutorIsClosedOnBeanDestruction() {
        ExecutorService ownedExecutor = Executors.newSingleThreadExecutor();
        AgentToolExecutor executor = new AgentToolExecutor(
                new AgentProperties(), domainConfig(), ownedExecutor, true);

        executor.shutdown();

        assertThat(ownedExecutor.isShutdown()).isTrue();
    }

    @Test
    void externallyProvidedExecutorIsNotClosedOnBeanDestruction() {
        ExecutorService externalExecutor = Executors.newSingleThreadExecutor();
        try {
            AgentToolExecutor executor = new AgentToolExecutor(
                    new AgentProperties(), domainConfig(), externalExecutor, false);

            executor.shutdown();

            assertThat(externalExecutor.isShutdown()).isFalse();
        } finally {
            externalExecutor.shutdownNow();
        }
    }

    private AgentDomainConfig domainConfig() {
        return new AgentDomainConfig(null, null, null, null);
    }
}
