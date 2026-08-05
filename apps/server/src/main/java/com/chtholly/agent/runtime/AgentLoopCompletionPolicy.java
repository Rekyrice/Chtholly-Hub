package com.chtholly.agent.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Applies domain-specific completion gates without coupling them to the loop state machine. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentLoopCompletionPolicy {

    private static final String WEB_RESEARCH_INCOMPLETE =
            "WEB_RESEARCH_INCOMPLETE: web_search results are discovery hints, not evidence. "
                    + "Call web_fetch for at least one result and use only returned Evidence.";

    /** Creates isolated completion state for one turn. */
    public CompletionState begin(AgentLoopRequest request) {
        boolean characterQuery = request.tools().containsKey("bangumi_search")
                && request.tools().containsKey("bangumi_characters")
                && asksForCharacters(request.question());
        return new CompletionState(characterQuery);
    }

    /** Records required-tool outcomes without presenting provider failures as successful calls. */
    public void recordToolResult(
            CompletionState state,
            String toolName,
            AgentToolResult.Status status) {
        if (state == null || status == null) {
            return;
        }
        if ("bangumi_search".equals(toolName)) {
            state.bangumiSearch = transition(state.bangumiSearch, status);
        } else if ("bangumi_characters".equals(toolName)) {
            state.bangumiCharacters = transition(state.bangumiCharacters, status);
        }
    }

    /** Evaluates whether a final action may leave the loop. */
    public CompletionGate evaluate(
            CompletionState state,
        AgentEvidenceTracker evidenceTracker) {
        if (state != null && state.charactersRequired) {
            if (state.bangumiSearch == RequiredToolState.PENDING) {
                return CompletionGate.pending(
                        "COMPOUND_QUERY_INCOMPLETE：作品资料查询尚未成功。"
                                + "请继续调用 bangumi_search，成功后再查询角色。",
                        "compound_tool_pending");
            }
            if (state.bangumiSearch == RequiredToolState.SATISFIED
                    && state.bangumiCharacters == RequiredToolState.PENDING) {
                return CompletionGate.pending(
                        "COMPOUND_QUERY_INCOMPLETE：角色查询尚未成功。"
                                + "请继续调用 bangumi_characters，再生成最终回答。",
                        "compound_tool_pending");
            }
        }
        AgentEvidenceTracker.WebResearchRequirement requirement =
                evidenceTracker.webResearchRequirement();
        if (requirement == AgentEvidenceTracker.WebResearchRequirement.SEARCH_RETRY_REQUIRED) {
            return CompletionGate.pending(
                    "SEARCH_RETRY_REQUIRED: web_search 未返回可抓取结果，请调整查询后重试。",
                    "web_search_retry_required");
        }
        if (requirement == AgentEvidenceTracker.WebResearchRequirement.FETCH_REQUIRED) {
            return CompletionGate.pending(WEB_RESEARCH_INCOMPLETE, "web_fetch_pending");
        }
        return CompletionGate.allowed();
    }

    private static RequiredToolState transition(
            RequiredToolState current,
            AgentToolResult.Status status) {
        if (current == RequiredToolState.SATISFIED
                || status == AgentToolResult.Status.VALIDATION_ERROR) {
            return current;
        }
        return status == AgentToolResult.Status.SUCCESS
                ? RequiredToolState.SATISFIED
                : RequiredToolState.BLOCKED;
    }

    private boolean asksForCharacters(String question) {
        String normalized = question == null ? "" : question.toLowerCase();
        return normalized.contains("角色")
                || normalized.contains("人物")
                || normalized.contains("登场")
                || normalized.contains("配角")
                || normalized.contains("character");
    }

    /** Mutable state owned exclusively by one loop invocation. */
    public static final class CompletionState {
        private final boolean charactersRequired;
        private RequiredToolState bangumiSearch = RequiredToolState.PENDING;
        private RequiredToolState bangumiCharacters = RequiredToolState.PENDING;

        private CompletionState(boolean charactersRequired) {
            this.charactersRequired = charactersRequired;
        }
    }

    private enum RequiredToolState {
        PENDING,
        SATISFIED,
        BLOCKED
    }

    /** Immutable decision returned to the loop. */
    public record CompletionGate(boolean ready, String observation, String traceAction) {
        private static CompletionGate allowed() {
            return new CompletionGate(true, "", "");
        }

        private static CompletionGate pending(String observation, String traceAction) {
            return new CompletionGate(false, observation, traceAction);
        }
    }
}
