package com.chtholly.agent.response;

import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.context.AgentContextSnapshot;
import com.chtholly.agent.evidence.EvidenceSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Builds the immutable prompt boundary used only by final-answer generation. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentFinalAnswerPromptFactory {

    private final AgentDomainConfig domainConfig;
    private final CharacterSoulService characterSoulService;

    /** Creates the final prompt factory. */
    public AgentFinalAnswerPromptFactory(
            AgentDomainConfig domainConfig,
            CharacterSoulService characterSoulService) {
        this.domainConfig = domainConfig;
        this.characterSoulService = characterSoulService;
    }

    /** Builds final-only system and user prompts from immutable context and loop transcript. */
    public Prompt build(
            AgentContextSnapshot contextSnapshot,
            EvidenceSet finalEvidenceSet,
            List<String> transcript) {
        String context = String.join("\n\n", transcript);
        String finalInstructions = domainConfig.render(
                domainConfig.systemPrompt().finalAnswerSystem(),
                "soul",
                characterSoulService.getSoulContent());
        String contextualSystem = contextSnapshot.renderFinalSystemPrompt(finalEvidenceSet);
        String system = contextualSystem.isBlank()
                ? finalInstructions
                : contextualSystem + "\n\n" + finalInstructions;
        String userPrompt = context + "\n\n"
                + domainConfig.systemPrompt().finalAnswerPrompt();
        return new Prompt(system, userPrompt);
    }

    /** Returns the configured client-safe response timeout message. */
    public String responseTimeout() {
        return domainConfig.errors().responseTimeout();
    }

    /** Returns the configured client-safe response failure message. */
    public String responseFailed() {
        return domainConfig.errors().responseFailed();
    }

    /** Immutable final-answer prompt pair. */
    public record Prompt(String system, String userPrompt) {
    }
}
