package com.chtholly.agent.mood;

import com.chtholly.agent.config.AgentExtensionComponent;
import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextContributor;
import com.chtholly.agent.context.ContextOrder;
import com.chtholly.agent.context.ContextRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Renders the optional season-aware prompt section. */
@Slf4j
@Component
@AgentExtensionComponent
@ConditionalOnProperty(prefix = "agent.extensions.mood", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SeasonalContextContributor implements ContextContributor {

    private final SeasonService seasonService;

    public SeasonalContextContributor(SeasonService seasonService) {
        this.seasonService = seasonService;
    }

    @Override
    public String name() {
        return "seasonal";
    }

    @Override
    public int order() {
        return ContextOrder.SEASONAL;
    }

    @Override
    public ContextContribution contribute(ContextRequest request) {
        try {
            String seasonalPrompt = seasonService.getSeasonalPrompt();
            if (seasonalPrompt == null || seasonalPrompt.isBlank()) {
                return ContextContribution.empty(name(), order(), false);
            }
            return new ContextContribution(
                    name(), order(), "## 季节感受\n\n" + seasonalPrompt.trim(), false);
        } catch (RuntimeException e) {
            log.warn("Seasonal context failed", e);
            return ContextContribution.empty(name(), order(), true);
        }
    }
}
