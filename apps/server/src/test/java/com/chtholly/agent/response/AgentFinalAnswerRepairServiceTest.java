package com.chtholly.agent.response;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentBoundedCallExecutor;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentFinalAnswerRepairServiceTest {

    private final AgentLlmInvoker llmInvoker = mock(AgentLlmInvoker.class);
    private final AgentObservationService observations = mock(AgentObservationService.class);
    private final Observation agentSpan = mock(Observation.class);
    private final Observation repairSpan = mock(Observation.class);
    private final AgentExecutionTrace trace = mock(AgentExecutionTrace.class);
    private final AgentProperties properties = new AgentProperties();
    private final AgentFinalAnswerRepairService service = new AgentFinalAnswerRepairService(
            llmInvoker,
            properties,
            observations,
            new AgentBoundedCallExecutor(),
            new AgentFinalAnswerProtocol(new ObjectMapper(), properties));

    @BeforeEach
    void setUp() {
        properties.setModel("test-model");
        when(observations.startLlmSpan(agentSpan, "test-model")).thenReturn(repairSpan);
    }

    @Test
    void repairsAnActionEnvelopeExactlyOnceIntoVisibleMarkdown() throws Exception {
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("修复后的 Markdown");

        String answer = service.repairActionEnvelope(
                "system",
                "prompt",
                trace,
                agentSpan,
                2,
                AgentTurnBudget.start(Duration.ofSeconds(1), () -> false));

        assertThat(answer).isEqualTo("修复后的 Markdown");
        verify(llmInvoker).call(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void failsClosedWhenTheSingleActionRepairStillReturnsAnEnvelope() throws Exception {
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"final\"}");

        assertThatThrownBy(() -> service.repairActionEnvelope(
                "system",
                "prompt",
                trace,
                agentSpan,
                2,
                AgentTurnBudget.start(Duration.ofSeconds(1), () -> false)))
                .isInstanceOf(AgentInvalidFinalAnswerException.class)
                .hasMessage("FINAL_ACTION_ENVELOPE");
    }

    @Test
    void rejectsCitationRepairThatChangesTheAnswerBody() throws Exception {
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("改写后的句子。[E1]");

        String repaired = service.repairMissingCitations(
                "原来的句子。",
                evidence(),
                trace,
                agentSpan,
                2,
                AgentTurnBudget.start(Duration.ofSeconds(1), () -> false));

        assertThat(repaired).isEqualTo("原来的句子。");
        verify(observations).startLlmSpan(agentSpan, "test-model");
        verify(observations).finishSpan(eq(repairSpan), anyMap(), anyMap());
    }

    private EvidenceSet evidence() {
        Evidence evidence = new Evidence(
                "ev-1", "POST", "post:1", "post:1", "post:1#0",
                "文章", "semantic", "v1", "hash", "正文",
                1, 0.9, Set.of("PUBLIC"), "E1");
        return EvidenceSet.of(List.of(evidence), Set.of("PUBLIC"));
    }
}
