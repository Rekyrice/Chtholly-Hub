package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.anchor.AnchorContext;
import com.chtholly.agent.skill.EvidencePolicy;

/** Runtime snapshot supplied to every context contributor. */
public record ContextRequest(
        long userId,
        String sessionId,
        String pageContext,
        Iterable<AgentTool> tools,
        String conversationHistory,
        String userQuestion,
        AnchorContext anchors,
        EvidencePolicy evidencePolicy,
        String retrievalQuery
) {

    public ContextRequest {
        evidencePolicy = evidencePolicy == null ? EvidencePolicy.NOT_NEEDED : evidencePolicy;
        retrievalQuery = retrievalQuery == null ? "" : retrievalQuery.strip();
    }

    public ContextRequest(
            long userId,
            String sessionId,
            String pageContext,
            Iterable<AgentTool> tools,
            String conversationHistory,
            String userQuestion,
            AnchorContext anchors) {
        this(
                userId,
                sessionId,
                pageContext,
                tools,
                conversationHistory,
                userQuestion,
                anchors,
                EvidencePolicy.NOT_NEEDED,
                "");
    }

    public ContextRequest(
            long userId,
            String sessionId,
            String pageContext,
            Iterable<AgentTool> tools,
            String conversationHistory,
            String userQuestion,
            AnchorContext anchors,
            boolean evidenceRequired) {
        this(
                userId,
                sessionId,
                pageContext,
                tools,
                conversationHistory,
                userQuestion,
                anchors,
                evidenceRequired ? EvidencePolicy.REQUIRED : EvidencePolicy.NOT_NEEDED,
                "");
    }

    public boolean evidenceRequired() {
        return evidencePolicy == EvidencePolicy.REQUIRED;
    }
}
