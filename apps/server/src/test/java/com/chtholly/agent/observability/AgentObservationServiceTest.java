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
    void startAgentSpanKeepsUniqueIdHighCardinalityAndDoesNotExportUserId() {
        Observation observation = service.startAgentSpan("corr-abc", 42L);
        try (Observation.Scope scope = observation.openScope()) {
            assertThat(observation.getContextView().getLowCardinalityKeyValues())
                    .noneMatch(kv -> kv.getKey().equals("agent.user_id"))
                    .noneMatch(kv -> kv.getKey().equals("agent.correlation_id"));
            assertThat(observation.getContextView().getHighCardinalityKeyValues())
                    .anyMatch(kv -> kv.getKey().equals("agent.correlation_id")
                            && kv.getValue().equals("corr-abc"))
                    .noneMatch(kv -> kv.getKey().equals("agent.user_id"));
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
    void skillRetrievalAndDraftSpansUseExistingHierarchy() {
        Observation agent = service.startAgentSpan("corr-2", 1L);
        try (Observation.Scope agentScope = agent.openScope()) {
            Observation skill = service.startSkillSpan(agent);
            service.finishSpan(skill, Map.of(
                    "skill.id", "page-explain",
                    "skill.version", "v1",
                    "status", "selected"), Map.of("selection.reason", "explicit_task_type"));

            Observation retrieval = service.startRetrievalSpan(agent, "document-rrf-v1");
            service.finishSpan(retrieval, Map.of("status", "success"), Map.of(
                    "retrieval.vector.status", "SUCCESS_RESULTS",
                    "retrieval.keyword.status", "SUCCESS_RESULTS",
                    "retrieval.entity.status", "SUCCESS_EMPTY"));

            Observation preview = service.startDraftPreviewSpan(agent, "v1");
            service.finishSpan(preview, Map.of("status", "created"), Map.of());
            Observation apply = service.startDraftApplySpan(agent, "v1");
            service.finishSpan(apply, Map.of("status", "applied"), Map.of());
        } finally {
            service.finishSpan(agent, Map.of("agent.status", "final_answer"));
        }

        assertThat(lifecycleEvents).containsExactly(
                "start:agent.run",
                "start:agent.skill.select", "stop:agent.skill.select",
                "start:agent.retrieval.search", "stop:agent.retrieval.search",
                "start:agent.draft.preview", "stop:agent.draft.preview",
                "start:agent.draft.apply", "stop:agent.draft.apply",
                "stop:agent.run");
    }

    @Test
    void finishSpanError_marksObservationAsError() {
        Observation observation = service.startAgentSpan("corr-err", 9L);
        service.finishSpanError(observation, "timeout", Map.of("agent.status", "timeout"));
        assertThat(lifecycleEvents).contains("start:agent.run", "stop:agent.run");
        assertThat(observation.getContextView().getError()).isNotNull();
    }

    @Test
    void finishSpanDropsUnapprovedLowCardinalityKeys() {
        Observation observation = service.startSkillSpan(null);

        service.finishSpan(observation, Map.of(
                "status", "selected",
                "skill.id", "page-explain",
                "preview.id", "unique-123"), Map.of());

        assertThat(observation.getContextView().getLowCardinalityKeyValues())
                .anyMatch(kv -> kv.getKey().equals("status") && kv.getValue().equals("selected"))
                .anyMatch(kv -> kv.getKey().equals("skill.id") && kv.getValue().equals("page-explain"))
                .noneMatch(kv -> kv.getKey().equals("preview.id"));
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
