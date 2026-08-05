package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentAction;
import com.chtholly.agent.AgentJsonExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parses model action envelopes and prepares stable tool input representations. */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentActionParser {

    private final AgentJsonExtractor jsonExtractor;
    private final ObjectMapper objectMapper;

    /**
     * Creates the action protocol parser.
     *
     * @param jsonExtractor action JSON extractor
     * @param objectMapper JSON mapper
     */
    public AgentActionParser(AgentJsonExtractor jsonExtractor, ObjectMapper objectMapper) {
        this.jsonExtractor = jsonExtractor;
        this.objectMapper = objectMapper;
    }

    /**
     * Parses one model decision and rejects non-object tool input as a recoverable protocol error.
     *
     * @param modelOutput raw model decision
     * @return parsed action
     * @throws Exception when the action envelope is malformed
     */
    public AgentAction parse(String modelOutput) throws Exception {
        String json = jsonExtractor.extractActionJson(modelOutput);
        JsonNode node = objectMapper.readTree(json);
        String action = node.path("action").asText(null);
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("missing action");
        }
        JsonNode input = node.path("input");
        if (!input.isMissingNode() && !input.isNull() && !input.isObject()) {
            throw new IllegalArgumentException("action input must be an object");
        }
        return new AgentAction(action, input.isMissingNode() || input.isNull() ? null : input);
    }

    /**
     * Builds the internal tool input envelope with current-question and conversation context.
     *
     * @param input validated action input
     * @param question current user question
     * @param historyBlock formatted conversation history
     * @return mutable ordered tool input map
     */
    public Map<String, Object> prepareToolInput(
            JsonNode input,
            String question,
            String historyBlock) {
        Map<String, Object> prepared = new LinkedHashMap<>();
        if (input != null && !input.isNull() && !input.isMissingNode()) {
            prepared.putAll(objectMapper.convertValue(input, Map.class));
        }
        prepared.keySet().removeIf(key -> key != null && key.startsWith("_"));
        prepared.put("_userQuestion", question);
        if (historyBlock != null && !historyBlock.isBlank()) {
            prepared.put("_conversationHistory", historyBlock);
        }
        return prepared;
    }

    /**
     * Serializes actual tool input for complete administrator trace capture.
     *
     * @param input tool input
     * @return serialized input or a stable fallback
     */
    public String serializeToolInput(Map<String, Object> input) {
        try {
            return objectMapper.writeValueAsString(input == null ? Map.of() : input);
        } catch (Exception exception) {
            log.debug("Agent tool trace input serialization fallback: {}",
                    exception.getClass().getName());
            return String.valueOf(input);
        }
    }

}
