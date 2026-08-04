package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.context.AgentContextSnapshot;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.agent.skill.SkillRequestPlanner;
import com.chtholly.agent.skill.SkillSelector;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTurnPreparationServiceTest {

    private final ContextEngine contextEngine = mock(ContextEngine.class);
    private final AgentObservationService observations = mock(AgentObservationService.class);
    private final SkillRegistry skillRegistry = mock(SkillRegistry.class);
    private final SkillSelector skillSelector = mock(SkillSelector.class);
    private final Observation agentSpan = mock(Observation.class);
    private final AgentExecutionTrace trace = mock(AgentExecutionTrace.class);
    private final AgentConversationMemory memory = mock(AgentConversationMemory.class);
    private final AgentTool search = tool("search");
    private final AgentTurnPreparationService service = new AgentTurnPreparationService(
            new AgentTurnPlanningService(
                    List.of(search),
                    new AgentToolPlanner(),
                    skillRegistry,
                    skillSelector,
                    new SkillRequestPlanner()),
            new AgentContextPreparationService(
                    contextEngine,
                    new AgentBoundedCallExecutor()),
            new AgentPreparationSpanLifecycle(observations));

    @Test
    void buildsTheImmutableLoopRequestForAGenericTurn() {
        when(skillRegistry.enabled()).thenReturn(List.of());
        when(skillSelector.select(any(), any())).thenReturn(new SkillSelector.SkillSelection(
                SkillSelector.Status.NO_MATCH, null, "none", 0.0, Set.of()));
        when(memory.formatForPrompt()).thenReturn("history");
        when(contextEngine.buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new AgentContextSnapshot("system", EvidenceSet.empty(), false));

        AgentTurnPreparationService.PreparedTurn prepared = service.prepare(request("question"));

        assertThat(prepared.status()).isEqualTo(AgentTurnPreparationService.Status.READY);
        assertThat(prepared.loopRequest().systemPrompt()).isEqualTo("system");
        assertThat(prepared.loopRequest().historyBlock()).isEqualTo("history");
        assertThat(prepared.loopRequest().tools()).containsOnlyKeys("search");
        assertThat(prepared.selectedSkill()).isNull();
    }

    @Test
    void returnsAClarificationBoundaryBeforeMemoryOrRetrieval() {
        when(skillRegistry.enabled()).thenReturn(List.of());
        when(skillSelector.select(any(), any())).thenReturn(new SkillSelector.SkillSelection(
                SkillSelector.Status.CLARIFICATION_REQUIRED,
                null,
                "ambiguous",
                0.0,
                Set.of()));

        AgentTurnPreparationService.PreparedTurn prepared = service.prepare(request("question"));

        assertThat(prepared.status()).isEqualTo(AgentTurnPreparationService.Status.BOUNDARY);
        assertThat(prepared.boundary().reason())
                .isEqualTo(AgentExecutionTrace.OutcomeReason.NEEDS_CLARIFICATION);
        assertThat(prepared.boundary().detail()).isEqualTo("ambiguous");
        verify(memory, never()).formatForPrompt();
        verify(contextEngine, never()).buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean());
    }

    private AgentTurnPreparationService.Request request(String question) {
        AgentTurnControl control = AgentTurnControl.standalone("session", Duration.ofSeconds(2));
        return new AgentTurnPreparationService.Request(
                question,
                7L,
                memory,
                "session",
                "page",
                null,
                4,
                trace,
                agentSpan,
                control.budget());
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                return "unused";
            }
        };
    }
}
