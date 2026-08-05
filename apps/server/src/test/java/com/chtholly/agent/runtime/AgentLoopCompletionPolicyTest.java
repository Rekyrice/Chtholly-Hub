package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.evidence.EvidenceSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentLoopCompletionPolicyTest {

    private final AgentLoopCompletionPolicy policy = new AgentLoopCompletionPolicy();

    @Test
    void compoundBangumiQueryAllowsGracefulFinalAfterRequiredProviderFailure() {
        AgentLoopRequest request = new AgentLoopRequest(
                "system",
                "查询评分并列出主要角色",
                7L,
                "",
                Map.of(
                        "bangumi_search", mock(AgentTool.class),
                        "bangumi_characters", mock(AgentTool.class)),
                10);
        AgentLoopCompletionPolicy.CompletionState state = policy.begin(request);
        AgentEvidenceTracker evidence = tracker();

        assertThat(policy.evaluate(state, evidence).traceAction())
                .isEqualTo("compound_tool_pending");
        policy.recordToolResult(state, "bangumi_search", AgentToolResult.Status.ERROR);
        assertThat(policy.evaluate(state, evidence).ready()).isTrue();
    }

    @Test
    void compoundBangumiQueryRequiresCharactersAfterSuccessfulSearchButAllowsTimeoutDegradation() {
        AgentLoopCompletionPolicy.CompletionState state = policy.begin(compoundRequest());
        AgentEvidenceTracker evidence = tracker();

        policy.recordToolResult(state, "bangumi_search", AgentToolResult.Status.SUCCESS);
        assertThat(policy.evaluate(state, evidence).observation())
                .contains("bangumi_characters");
        policy.recordToolResult(state, "bangumi_characters", AgentToolResult.Status.TIMEOUT);

        assertThat(policy.evaluate(state, evidence).ready()).isTrue();
    }

    @Test
    void compoundBangumiValidationErrorStillRequiresCorrectedParameters() {
        AgentLoopCompletionPolicy.CompletionState state = policy.begin(compoundRequest());

        policy.recordToolResult(
                state, "bangumi_search", AgentToolResult.Status.VALIDATION_ERROR);

        assertThat(policy.evaluate(state, tracker()).ready()).isFalse();
        assertThat(policy.evaluate(state, tracker()).observation())
                .contains("bangumi_search");
    }

    @Test
    void webResearchGatePreservesItsExplicitNextAction() {
        AgentLoopCompletionPolicy.CompletionState state = policy.begin(new AgentLoopRequest(
                "system", "research", 7L, "", Map.of(), 10));
        AgentEvidenceTracker evidence = tracker();
        evidence.recordSuccessfulWebSearch("malformed");

        AgentLoopCompletionPolicy.CompletionGate gate = policy.evaluate(state, evidence);

        assertThat(gate.ready()).isFalse();
        assertThat(gate.traceAction()).isEqualTo("web_search_retry_required");
    }

    private AgentEvidenceTracker tracker() {
        return new AgentEvidenceTracker(EvidenceSet.empty(), false, new ObjectMapper());
    }

    private AgentLoopRequest compoundRequest() {
        return new AgentLoopRequest(
                "system",
                "查询评分并列出主要角色",
                7L,
                "",
                Map.of(
                        "bangumi_search", mock(AgentTool.class),
                        "bangumi_characters", mock(AgentTool.class)),
                10);
    }
}
