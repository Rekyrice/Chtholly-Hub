package com.chtholly.agent.learning;

import com.chtholly.agent.config.AgentExtensionComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async executor for low-priority agent learning jobs.
 */
@Configuration
@AgentExtensionComponent
@ConditionalOnProperty(prefix = "agent.extensions.learning", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentLearningAsyncConfig {

    @Bean(name = "agentExecutor")
    public TaskExecutor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-learning-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
