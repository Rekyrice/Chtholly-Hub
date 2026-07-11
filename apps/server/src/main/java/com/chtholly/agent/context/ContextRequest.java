package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.anchor.AnchorContext;

/** Runtime snapshot supplied to every context contributor. */
public record ContextRequest(
        long userId,
        String sessionId,
        String pageContext,
        Iterable<AgentTool> tools,
        String conversationHistory,
        String userQuestion,
        AnchorContext anchors
) {
}
