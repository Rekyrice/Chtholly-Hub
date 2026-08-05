package com.chtholly.agent.tools;

import com.chtholly.agent.observability.AgentTraceSanitizer;
import com.chtholly.agent.runtime.AgentToolExecutionException;

import java.util.Map;

/** Shared controlled-failure mapping for Bangumi-backed Agent tools. */
final class BangumiToolFailures {

    static final String ERROR_CODE = "BANGUMI_UNAVAILABLE";
    private static final String FALLBACK_MESSAGE = "Bangumi 服务暂时不可用，请稍后再试。";

    private BangumiToolFailures() {
    }

    static AgentToolExecutionException unavailable(IllegalStateException cause) {
        String safeMessage = AgentTraceSanitizer.safeMessage(cause.getMessage());
        String userMessage = safeMessage == null || safeMessage.isBlank()
                ? FALLBACK_MESSAGE
                : safeMessage;
        return new AgentToolExecutionException(
                ERROR_CODE,
                userMessage,
                Map.of("provider", "bangumi"),
                cause);
    }
}
