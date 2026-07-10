package com.chtholly.agent.config;

import java.util.List;

/** Prompt fragments used by the ReAct loop and final answer generation. */
public record AgentSystemPromptConfig(
        String errorFallback,
        String parseErrorObservation,
        String parseErrorThink,
        String finalAnswerSystem,
        String finalAnswerPrompt,
        String finalThinking,
        String toolThinking,
        String siteEmptyGuidance,
        String bangumiTimeoutGuidance,
        List<String> emptySiteResultMarkers
) { }
