package com.chtholly.agent.content;

import com.chtholly.agent.config.AgentExtensionComponent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Asynchronously initializes a missing topic-cluster snapshot after application startup.
 */
@Slf4j
@Component
@AgentExtensionComponent
@ConditionalOnProperty(prefix = "agent.extensions.content", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class TopicClusterWarmup {

    private final TopicClusteringService topicClusteringService;

    /**
     * Refreshes topic clusters after startup only when persisted snapshot data is incomplete.
     */
    @Async("taskExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void refreshIfMissing() {
        try {
            topicClusteringService.refreshIfMissing();
        } catch (RuntimeException e) {
            log.warn("Topic cluster warmup failed", e);
        }
    }
}
