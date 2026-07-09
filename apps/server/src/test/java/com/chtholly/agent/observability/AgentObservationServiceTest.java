package com.chtholly.agent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentObservationServiceTest {

    private final List<String> lifecycleEvents = new ArrayList<>();
    private AgentObservationService service;

    @BeforeEach
    void setUp() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new RecordingHandler(lifecycleEvents));
        service = new AgentObservationService(registry);
    }

    @Test
    void startAgentSpan_attachesUserAndCorrelationAttributes() {
        Observation observation = service.startAgentSpan("corr-abc", 42L);
        try (Observation.Scope scope = observation.openScope()) {
            assertThat(observation.getContextView().getLowCardinalityKeyValues())
                    .anyMatch(kv -> kv.getKey().equals("agent.user_id") && kv.getValue().equals("42"))
                    .anyMatch(kv -> kv.getKey().equals("agent.correlation_id") && kv.getValue().equals("corr-abc"));
        } finally {
            service.finishSpan(observation, Map.of("agent.status", "final_answer"));
        }
        assertThat(lifecycleEvents).contains("start:agent.run", "stop:agent.run");
    }

    @Test
    void llmAndToolSpans_formHierarchyUnderAgentSpan() {
        Observation agent = service.startAgentSpan("corr-1", 1L);
        try (Observation.Scope agentScope = agent.openScope()) {
            Observation llm = service.startLlmSpan(agent, "deepseek-chat");
            service.finishSpan(llm, Map.of("llm.status", "ok"));

            Observation tool = service.startToolSpan(agent, "fulltext_search");
            service.finishSpan(tool, Map.of("tool.success", "true"));
        } finally {
            service.finishSpan(agent, Map.of("agent.status", "final_answer"));
        }

        assertThat(lifecycleEvents).containsExactly(
                "start:agent.run",
                "start:agent.llm.call",
                "stop:agent.llm.call",
                "start:agent.tool.execute",
                "stop:agent.tool.execute",
                "stop:agent.run");
    }

    @Test
    void finishSpanError_marksObservationAsError() {
        Observation observation = service.startAgentSpan("corr-err", 9L);
        service.finishSpanError(observation, "timeout", Map.of("agent.status", "timeout"));
        assertThat(lifecycleEvents).contains("start:agent.run", "stop:agent.run");
        assertThat(observation.getContextView().getError()).isNotNull();
    }

    private static final class RecordingHandler implements ObservationHandler<Observation.Context> {
        private final List<String> events;

        private RecordingHandler(List<String> events) {
            this.events = events;
        }

        @Override
        public void onStart(Observation.Context context) {
            events.add("start:" + context.getName());
        }

        @Override
        public void onStop(Observation.Context context) {
            events.add("stop:" + context.getName());
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}
