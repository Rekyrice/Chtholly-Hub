package com.chtholly.agent.runtime;

import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolExecutorLifecycleTest {

    @Test
    void ownedExecutorIsClosedOnBeanDestruction() throws Exception {
        AgentToolExecutor executor = new AgentToolExecutor(new AgentProperties(), domainConfig());
        ExecutorService ownedExecutor = executorField(executor);

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

    private ExecutorService executorField(AgentToolExecutor executor) throws Exception {
        Field field = AgentToolExecutor.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ExecutorService) field.get(executor);
    }

    private AgentDomainConfig domainConfig() {
        return new AgentDomainConfig(null, null, null, null);
    }
}
