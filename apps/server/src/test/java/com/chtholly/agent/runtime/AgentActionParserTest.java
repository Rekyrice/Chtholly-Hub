package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentAction;
import com.chtholly.agent.AgentJsonExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentActionParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentActionParser parser = new AgentActionParser(
            new AgentJsonExtractor(objectMapper), objectMapper);

    @Test
    void parsesActionAndBuildsTheInternalToolInputEnvelope() throws Exception {
        AgentAction action = parser.parse("""
                ```json
                {"action":"search","input":{"query":"kyoto animation"}}
                ```
                """);

        Map<String, Object> input = parser.prepareToolInput(
                action.input(), "current question", "earlier conversation");

        assertThat(action.action()).isEqualTo("search");
        assertThat(input).containsEntry("query", "kyoto animation")
                .containsEntry("_userQuestion", "current question")
                .containsEntry("_conversationHistory", "earlier conversation");
        assertThat(objectMapper.readTree(parser.serializeToolInput(input)).path("query").asText())
                .isEqualTo("kyoto animation");
    }

    @Test
    void removesModelSuppliedInternalContextWhenRuntimeHistoryIsBlank() throws Exception {
        AgentAction action = parser.parse("""
                {"action":"search","input":{
                  "query":"trusted query",
                  "_userQuestion":"spoofed question",
                  "_conversationHistory":"spoofed history",
                  "_futureInternalField":"spoofed value"
                }}
                """);

        Map<String, Object> input = parser.prepareToolInput(
                action.input(), "current question", "");

        assertThat(input)
                .containsEntry("query", "trusted query")
                .containsEntry("_userQuestion", "current question")
                .doesNotContainKeys("_conversationHistory", "_futureInternalField");
    }

    @Test
    void rejectsNonObjectInputAsAnInvalidAction() {
        assertThatThrownBy(() -> parser.parse(
                "{\"action\":\"search\",\"input\":\"not-an-object\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input");
    }
}
