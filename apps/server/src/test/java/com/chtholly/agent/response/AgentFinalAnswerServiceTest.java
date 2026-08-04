package com.chtholly.agent.response;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.context.AgentContextSnapshot;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.agent.runtime.AgentTurnCompletion;
import com.chtholly.agent.runtime.AgentTurnControl;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentFinalAnswerServiceTest {

    @Test
    void reportsInitialAndRepairModelDurationsAsOneFinalStepDuration() {
        AgentFinalCandidateGenerator generator = mock(AgentFinalCandidateGenerator.class);
        AgentFinalAnswerValidationPipeline validation =
                mock(AgentFinalAnswerValidationPipeline.class);
        AgentTurnCompletion completion = mock(AgentTurnCompletion.class);
        AgentExecutionTrace trace = mock(AgentExecutionTrace.class);
        AgentTurnControl control = mock(AgentTurnControl.class);
        when(trace.getTurnControl()).thenReturn(control);
        when(trace.getStartedAtMs()).thenReturn(System.currentTimeMillis());
        when(generator.generate(any())).thenReturn(new AgentFinalCandidateGenerator.Result(
                AgentFinalCandidateGenerator.Status.SUCCESS,
                "draft",
                "system",
                "prompt",
                23L,
                11L,
                false,
                "",
                null));
        when(validation.validate(any())).thenReturn(new AgentFinalAnswerValidationPipeline.Result(
                AgentFinalAnswerValidationPipeline.Status.APPROVED,
                "answer",
                AgentExecutionTrace.OutcomeReason.NONE,
                "",
                "",
                19L));
        AgentFinalAnswerService service = new AgentFinalAnswerService(
                generator,
                validation,
                mock(AgentBoundaryResponseService.class),
                completion,
                mock(AgentFinalAnswerPromptFactory.class));

        @SuppressWarnings("unchecked")
        Consumer<AgentEvent> sink = mock(Consumer.class);
        AgentConversationMemory memory = mock(AgentConversationMemory.class);
        AgentTurnBudget budget = AgentTurnBudget.start(Duration.ofSeconds(1), () -> false);
        long durationMs = service.stream(
                sink,
                "question",
                List.of("transcript"),
                memory,
                trace,
                mock(Observation.class),
                2,
                mock(AgentContextSnapshot.class),
                EvidenceSet.empty(),
                false,
                null,
                budget);

        assertThat(durationMs).isEqualTo(42L);
        verify(completion).completeVisibleAnswer(
                eq(memory),
                eq("question"),
                eq("answer"),
                eq(budget),
                eq(control),
                eq(trace),
                eq(sink),
                eq(11L),
                anyLong());
    }
}
